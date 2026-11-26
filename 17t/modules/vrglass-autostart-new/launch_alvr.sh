#!/system/bin/sh
# 进 VR 桌面后默认打开原版 ALVR 串流 (com.lht.huaweivr.alvr 20.14.1)。
#
# 背景：原版 ALVR 的 app_SdkLibs/classes.dex 被剥掉了华为 VR 框架的 compat 类
# (VrPlatformCompat / VRSystemServiceManagerImpl / IVRSystemService 系列)，
# 一启动就 NoClassDefFoundError 崩溃。我们给它补一个补丁 dex，并用 chattr +i
# 锁住，防止 ALVR 启动时从共享源(cur_cls.dex)重新解压覆盖。
#
# 不卸载官方 ALVR、不换签名、不丢数据 —— 只改它私有目录的一个 dex 文件。
#
# 用法：sh launch_alvr.sh
# 回滚：chattr -i <alvr classes.dex>; cp classes.dex.pre-compat classes.dex

ALVR=com.lht.huaweivr.alvr
SDK=/data/user/0/$ALVR/app_SdkLibs
DEX=$SDK/classes.dex
MODDIR=${0%/*}
PATCH=$MODDIR/alvr_sdk.fullcompat.dex           # 补好类的 dex (随模块部署,重启不丢)
MARK=/data/local/tmp/.alvr_patched              # 补丁版本标记
LOG=/data/local/tmp/alvr_launch.log
DISPLAY=1

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

# 1) 确保补丁 dex 存在且已部署 (ALVR 每次可能重新解压覆盖，这里幂等重写)
if [ -f "$PATCH" ]; then
  need=0
  [ -f "$MARK" ] || need=1
  # 若 dex 被还原(不含 compat)则重新打补丁
  if ! grep -aq "VrPlatformCompat" "$DEX" 2>/dev/null; then need=1; fi
  if [ "$need" = "1" ]; then
    chattr -i "$DEX" 2>/dev/null
    cp -f "$PATCH" "$DEX"
    chown u0_a225:u0_a225 "$DEX" 2>/dev/null
    chmod 600 "$DEX" 2>/dev/null
    rm -rf "$SDK/oat" 2>/dev/null
    chattr +i "$DEX" 2>/dev/null
    echo ok > "$MARK"
    log "patched ALVR classes.dex + chattr lock"
  fi
else
  log "WARN: patch dex $PATCH missing"
fi

# 1.5) 修 native OpenXR loader：ALVR native 要 dlopen("libopenxr_loader.so")，
#      但官方 APK 里只有 libxr_loader.so，名字对不上 -> panic。
#      复制一份真实文件并改名（软链不行：Android classloader 不认）。
ALVR_LIBDIR=$(pm path "$ALVR" 2>/dev/null | sed 's/package://' | sed 's|base.apk||')
ALVR_LIBDIR="${ALVR_LIBDIR}lib/arm"
if [ -f "$ALVR_LIBDIR/libxr_loader.so" ] && [ ! -f "$ALVR_LIBDIR/libopenxr_loader.so" ]; then
  cp "$ALVR_LIBDIR/libxr_loader.so" "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  chown system:system "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  chmod 755 "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  log "created libopenxr_loader.so"
fi

# 2) 等桌面在头显屏稳定 (MainActivity resumed)
i=0
while [ "$i" -lt 20 ]; do
  dumpsys activity activities 2>/dev/null | grep -qE 'mResumedActivity.*com\.huawei\.vrlauncherx/\.MainActivity' && break
  i=$((i+1)); sleep 1
done

# 3) 探测 HDMI display id (不写死)
DID=$(dumpsys display 2>/dev/null | grep -B8 'mPrimaryDisplayDevice=HDMI' | grep -o 'mDisplayId=[0-9]*' | tail -1 | cut -d= -f2)
[ -z "$DID" ] && DID=$DISPLAY
log "launching ALVR on display $DID"
am start --windowingMode 1 --display "$DID" \
  -a com.huawei.android.vr.action.MAIN -c android.intent.category.DEFAULT \
  -n $ALVR/.HuaweiVrActivity >> "$LOG" 2>&1
log "am start rc=$?"
