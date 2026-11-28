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
MARK2=/data/local/tmp/.alvr_patched_qcom        # 高通解码器版标记 (2026-11-28)
LOG=/data/local/tmp/alvr_launch.log
DISPLAY=1

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

# 1) 确保补丁 dex 存在且已部署 (ALVR 每次可能重新解压覆盖，这里幂等重写)
if [ -f "$PATCH" ]; then
  need=0
  [ -f "$MARK" ] || need=1
  # 若 dex 被还原(不含 compat)则重新打补丁
  if ! grep -aq "VrPlatformCompat" "$DEX" 2>/dev/null; then need=1; fi
  # 2026-11-28 高通解码器版升级：设备端仍是 hisi OMX 名或没有 qcom 标记时必须刷新。
  # 只查 VrPlatformCompat 会把旧 hisi dex 当成已补丁而跳过，导致 qcom dex 永远不落地。
  [ -f "$MARK2" ] || need=1
  if grep -aq "OMX.hisi.video.decoder" "$DEX" 2>/dev/null; then need=1; fi
  if [ "$need" = "1" ]; then
    chattr -i "$DEX" 2>/dev/null
    cp -f "$PATCH" "$DEX"
    chown u0_a225:u0_a225 "$DEX" 2>/dev/null
    chmod 600 "$DEX" 2>/dev/null
    rm -rf "$SDK/oat" 2>/dev/null
    chattr +i "$DEX" 2>/dev/null
    echo ok > "$MARK"
    echo qcom > "$MARK2"
    log "patched ALVR classes.dex (qcom decoder) + chattr lock"
  fi
else
  log "WARN: patch dex $PATCH missing"
fi

# 1.5) OpenXR loader 必须用 74KB 的 Khronos loader 前端(md5 f864cac5)，
#      它会再 dlopen APK 自带的 965KB libxr_loader.so (华为 runtime)。
#      2026-11-27 教训：把 965KB 那个直接改名当 loader 会跳过扩展枚举，
#      在 entry_point_with_window 的 initialize_android_loader().unwrap()
#      直接 ERROR_HANDLE_INVALID panic；74K+965K 组合才能正常枚举扩展。
ALVR_LIBDIR=$(pm path "$ALVR" 2>/dev/null | sed 's/package://' | sed 's|base.apk||')
ALVR_LIBDIR="${ALVR_LIBDIR}lib/arm"
if [ -f "$MODDIR/libopenxr_loader.74k.so" ]; then
  cp -f "$MODDIR/libopenxr_loader.74k.so" "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  chown system:system "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  chmod 755 "$ALVR_LIBDIR/libopenxr_loader.so" 2>/dev/null
  log "installed 74K OpenXR loader (f864cac5)"
fi

# 1.6) hvrframework.dex: 原版 ALVR 没有 com.huawei.android.permission.VR
#      (signature)，VrParamsRequester.bindVrService/bindVrLauncherx/
#      bindRuntimeService 三个方法绑定华为服务时抛 SecurityException；
#      该异常在 native 渲染线程冒泡成 JavaException，导致
#      app_dirs2 config_path() panic。三个方法对串流非必需，补丁 dex
#      直接 return-void。
HVR=$SDK/hvrframework.dex
if [ -f "$MODDIR/alvr_hvr.bindfix.dex" ]; then
  if ! grep -aq '2026-11-27' "$HVR" 2>/dev/null; then
    chattr -i "$HVR" 2>/dev/null
    cp -f "$MODDIR/alvr_hvr.bindfix.dex" "$HVR"
    chown u0_a225:u0_a225 "$HVR" 2>/dev/null
    chmod 600 "$HVR" 2>/dev/null
    rm -rf "$SDK/oat" 2>/dev/null
    log "patched hvrframework.dex (skip 3 bindService calls)"
  fi
fi

# 1.7) 危险权限：zygote 重启/清后台后可能被撤销，幂等回补。
for p in android.permission.RECORD_AUDIO android.permission.READ_EXTERNAL_STORAGE \
         android.permission.WRITE_EXTERNAL_STORAGE android.permission.ACCESS_MEDIA_LOCATION; do
  pm grant "$ALVR" "$p" >/dev/null 2>&1
done

# 1.8) 兜底重置 manufacturer。真正的持久化在本模块 system.prop
#      (Magisk 在 zygote 启动前应用，使 Build.MANUFACTURER=HUAWEI，
#       ALVR native 才会走 HuaweiVrGlass 分支而非通用分支)。
resetprop ro.product.manufacturer HUAWEI 2>/dev/null

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
