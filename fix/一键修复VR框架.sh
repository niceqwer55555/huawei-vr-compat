#!/bin/sh
# =============================================================================
# 一键修复 VR 框架 / 打通 VR 桌面链路（本地入口：Git Bash / Linux / macOS）
#
#   sh fix/一键修复VR框架.sh                          # 默认 install（四包 + 点亮链路）
#   sh fix/一键修复VR框架.sh <serial> check           # 只读验收
#   sh fix/一键修复VR框架.sh <serial> off             # 关掉模拟辅助显示
#   sh fix/一键修复VR框架.sh <serial> rollback        # 装回四个原版基线 APK
#   sh fix/一键修复VR框架.sh <serial> launcher        # 单独启动 3D Launcher
#
# 2026-11-05 更新：四个最终包，各自解决一段真实根因。
#
#   ① launcherx-full.apk
#      清单声明的 HvrContextProvider / VrSystemRegistryProvider 在 dex 里不存在
#      -> 注入现成 compat classes4.dex + 补 ctx1；
#      删除 classes.dex 里 6 个空壳桩类；恢复 LibUpdateClient.runUpdate()
#      （native SetClass 唯一入口）；
#      新增 HvrSdkBridge：接住 native SetClass 实际持有的 Class 副本，在**那一份**
#      上反射调用 VrFramework.init / VrHelmetExt.init，修掉两份 DexClassLoader
#      造成的 VrFramework.mAppActivity = null；
#      HVRActivity.getSystemService("vr_system") 返回同加载器动态代理，
#      其 isVRMode() 从 runtime 发布的 vr_system_state 读真实 SystemServiceBinder。
#   ② vrservice-full.apk
#      ctx0 provider 挂进 <application>；补定义 signature 级
#      com.huawei.android.permission.VR（EMUI 上由框架定义，通用安卓没有）。
#   ③ hvrsdkserverapp-full.apk
#      5 个补丁组件克隆进 <application>；27 个 64 位库固化进 lib/arm64-v8a/
#      （修 MyService dlopen 失败）；新增 HvrStatePublisher 把真实
#      SystemServiceBinder 发布到 vr_system_state。
#   ④ com.huawei.vrhandle-pose.apk  ★本次新增★
#      32 位 vrhandle 原本用 createPackageContext(runtime).nativeLibraryDir 拼
#      System.load 绝对路径加载 libMobileSensor/libWalleAlg/libWalleDev/libvrpose，
#      但 runtime 现在是 64 位（只解压 lib/arm64/，全是 ELF64），32 位进程加载
#      必然失败且被 catch 吞掉 -> helmetPoseStart() 无 native 实现 ->
#      VRHandleService 崩溃循环 -> 头盔姿态/pHelmetHandle 链断裂。
#      修法：4 个 ELF32 库打进本包 lib/armeabi-v7a/、manifest 改
#      extractNativeLibs=true 让系统解压到 lib/arm/、新增 HvrPoseLibPath.get()
#      优先返回自身 nativeLibraryDir。不动 runtime / 不动系统分区。
#      重签后 com.huawei.android.permission.VR 也自动 granted。
#
# 不改开机脚本、不重启、不动系统分区，随时可 rollback。
#
# 2026-11-05 真机验收（45s 冷启动）：
#   load libMobileSensor/WalleAlg/WalleDev/vrpose success 各 1；helmetPoseStart 执行；
#   getHelmetPose 返回四元数并被 launcherx 的 VrControllerManager 收到
#   （SetHelmetPoseOffset）；RenderModule "Init VR end" / mRenderInited 1；
#   UnsatisfiedLinkError / FATAL / ApplicationQuit / App activity is null /
#   VRPromptActivity 全部 0；栈顶保持 com.huawei.vrlauncherx/.MainActivity。
#
# ⚠️ 已知剩余阻塞（详见手册第 8 章）：约 2 分钟后 ArvrRuntimeService ANR →
#    launcherx 重启 → vrservice 弹 ServiceTermActivity 因 compat 桩类缺
#    setDisplaySideMode(I)V 崩溃 → 级联回落；另有 VRRenderWalleImpl
#    "No valid frame to display"（窗口 surface 未就绪）。属下一层，未解。
# =============================================================================
set -u

SERIAL="${1:-192.168.43.133:5555}"
ACTION="${2:-install}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
    *MINGW*|*MSYS*|*CYGWIN*)
        command -v cygpath >/dev/null 2>&1 && HERE="$(cygpath -m "$HERE")" ;;
esac
PATCH="$HERE/patches/vrdesktop"
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

find_adb() {
    if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then printf '%s' "$ADB"; return 0; fi
    if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
    for c in "/d/Tools/AndroidSDK/platform-tools/adb.exe" \
             "/f/Tools/AndroidSDK/platform-tools/adb.exe" \
             "/c/platform-tools/adb.exe" \
             "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
        [ -x "$c" ] && { printf '%s' "$c"; return 0; }
    done
    return 1
}
ADB="$(find_adb)" || { echo "[FATAL] 找不到 adb"; exit 1; }

L="$PATCH/com.huawei.vrlauncherx-full.apk"
V="$PATCH/com.huawei.vrservice-full.apk"
R="$PATCH/com.huawei.hvrsdkserverapp-full.apk"
H="$PATCH/com.huawei.vrhandle-pose.apk"
LB="$PATCH/rollback-launcherx.apk"
VB="$PATCH/rollback-vrservice.apk"
RB="$HERE/apks/com.huawei.hvrsdkserverapp.apk"
HB="$HERE/apks/com.huawei.vrhandle.apk"

echo "[i] adb    = $ADB"
echo "[i] 设备   = $SERIAL"
echo "[i] 动作   = $ACTION"
"$ADB" connect "$SERIAL" >/dev/null 2>&1
"$ADB" -s "$SERIAL" wait-for-device >/dev/null 2>&1 || {
    echo "[FATAL] 设备连不上（检查开机 / 无线 adb）"; exit 1; }

# vrhandle 补丁包是 hvr-compat 签名，设备基线可能是华为原签；
# 只有明确报「签名冲突」才卸载重装。无线 adb 常掉线，绝不能把连接失败
# 误判成签名冲突而卸载包，否则会留下缺包状态。回退时同理。
install_apk() {
    pkg="$1"; apk="$2"
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
    out="$("$ADB" -s "$SERIAL" install -r "$apk" 2>&1)"
    if printf '%s' "$out" | grep -q Success; then
        echo "      覆盖安装成功"; return 0
    fi
    if ! printf '%s' "$out" | grep -qiE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signature"; then
        echo "[FATAL] $pkg 安装失败且不是签名冲突，不卸载以免留下缺包："
        printf '%s\n' "$out" | tail -4
        return 1
    fi
    echo "      签名不一致，卸载后重装 ..."
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" uninstall "$pkg" >/dev/null 2>&1
    if "$ADB" -s "$SERIAL" install -r "$apk" 2>&1 | grep -q Success; then
        echo "      卸载重装成功"; return 0
    fi
    echo "[FATAL] $pkg 卸载后重装失败"; return 1
}

uid_of() {
    "$ADB" -s "$SERIAL" shell "pm list packages -U $1" 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*uid://p' | head -1
}

verify() {
    echo
    echo "===== 验收 ====="
    echo "-- 各包 hvrcompat provider --"
    for p in com.huawei.vrlauncherx com.huawei.vrservice com.huawei.hvrsdkserverapp; do
        echo "  [$p]"
        "$ADB" -s "$SERIAL" shell "dumpsys package $p | grep hvrcompat" | head -5
    done
    echo "-- runtime 64 位原生库数量（应为 30）--"
    RDIR="$("$ADB" -s "$SERIAL" shell "pm path com.huawei.hvrsdkserverapp" | tr -d '\r' | sed 's/package://;s|/base.apk||')"
    "$ADB" -s "$SERIAL" shell "ls $RDIR/lib/arm64/*.so 2>/dev/null | wc -l"
    echo "-- vrhandle 32 位姿态库（应为 5 个，全部 ELF32）--"
    HDIR="$("$ADB" -s "$SERIAL" shell "pm path com.huawei.vrhandle" | tr -d '\r' | sed 's/package://;s|/base.apk||')"
    "$ADB" -s "$SERIAL" shell "ls -l $HDIR/lib/arm/*.so 2>/dev/null"
    echo "-- VR 权限是否已定义 --"
    "$ADB" -s "$SERIAL" shell "dumpsys package permissions | grep -m1 'com.huawei.android.permission.VR]'"
    echo "-- 模拟辅助显示（null=未开）--"
    "$ADB" -s "$SERIAL" shell "settings get global overlay_display_devices"
    echo "-- 最近日志关键指标（计数）--"
    "$ADB" -s "$SERIAL" logcat -d -v threadtime 2>/dev/null | awk '
        /load libvrpose success/    { ok1++ }
        /helmetPoseStart/           { ok2++ }
        /getHelmetPose/             { ok3++ }
        /No valid frame to display/ { warn++ }
        /UnsatisfiedLinkError/      { e1++ }
        /ApplicationQuit/           { e2++ }
        /App activity is null/      { e3++ }
        /FATAL EXCEPTION/           { e4++ }
        END {
            printf "  %-34s %d\n", "load libvrpose success",    ok1+0
            printf "  %-34s %d\n", "helmetPoseStart",            ok2+0
            printf "  %-34s %d\n", "getHelmetPose",              ok3+0
            printf "  %-34s %d\n", "No valid frame to display",  warn+0
            printf "  %-34s %d\n", "UnsatisfiedLinkError",       e1+0
            printf "  %-34s %d\n", "ApplicationQuit",            e2+0
            printf "  %-34s %d\n", "App activity is null",       e3+0
            printf "  %-34s %d\n", "FATAL EXCEPTION",            e4+0
        }'
    echo "-- VR 进程 --"
    "$ADB" -s "$SERIAL" shell "ps -A | grep -E 'vrservice|vrlauncherx|hvrsdk|vrhandle'"
    echo "-- 栈顶 --"
    "$ADB" -s "$SERIAL" shell "dumpsys activity activities | grep mResumedActivity"
    echo
    echo "[i] 期望：provider 齐全、runtime 30 个库、vrhandle lib/arm 5 个 32 位库、"
    echo "    overlay 有值、helmetPoseStart 成功、无 UnsatisfiedLinkError / ApplicationQuit，"
    echo "    栈顶 com.huawei.vrlauncherx/.MainActivity。"
    echo "[i] 上面的计数是 logcat 缓冲区的历史值：成功项为 0 通常只是已滚出缓冲区；"
    echo "    若 FATAL EXCEPTION 非 0，先看时间戳是否早于本次冷启动，可先 logcat -c 再重跑。"
    echo "[i] 剩余阻塞：ArvrRuntimeService ANR → ServiceTermActivity 级联崩溃、"
    echo "    No valid frame to display，见手册第 8 章。"
}

case "$ACTION" in
    check)
        verify
        ;;
    off)
        echo "[i] 关闭模拟辅助显示 ..."
        "$ADB" -s "$SERIAL" shell "settings delete global overlay_display_devices"
        verify
        ;;
    rollback)
        for f in "$LB" "$VB" "$RB" "$HB"; do
            [ -f "$f" ] || { echo "[FATAL] 缺回退基线 APK: $f"; exit 1; }
        done
        echo "[1/5] 回退 vrservice ...";       install_apk com.huawei.vrservice "$VB" || exit 1
        echo "[2/5] 回退 hvrsdkserverapp ..."; install_apk com.huawei.hvrsdkserverapp "$RB" || exit 1
        echo "[3/5] 回退 launcherx ...";       install_apk com.huawei.vrlauncherx "$LB" || exit 1
        echo "[4/5] 回退 vrhandle（华为原签）..."; install_apk com.huawei.vrhandle "$HB" || exit 1
        echo "[5/5] 关闭模拟辅助显示 ..."
        "$ADB" -s "$SERIAL" shell "settings delete global overlay_display_devices"
        verify
        ;;
    launcher)
        "$ADB" -s "$SERIAL" shell "am start -n com.huawei.vrlauncherx/.MainActivity"
        sleep 3
        verify
        ;;
    install|*)
        for f in "$L" "$V" "$R" "$H"; do
            [ -f "$f" ] || { echo "[FATAL] 缺少补丁 APK: $f"; exit 1; }
        done
        echo "[1/9] 安装 vrservice（ctx0 + 定义 VR 权限）..."
        install_apk com.huawei.vrservice "$V" || exit 1
        echo "[2/9] 安装 hvrsdkserverapp（组件归位 + 64 位库 + vr_system_state，161MB 稍慢）..."
        install_apk com.huawei.hvrsdkserverapp "$R" || exit 1
        echo "[3/9] 安装 launcherx（compat + 去桩 + runUpdate + HvrSdkBridge）..."
        install_apk com.huawei.vrlauncherx "$L" || exit 1
        echo "[4/9] 安装 vrhandle（32 位姿态库 + HvrPoseLibPath）★ ..."
        install_apk com.huawei.vrhandle "$H" || exit 1
        sleep 4
        echo "[5/9] 打开模拟辅助显示（在位判定前提）..."
        "$ADB" -s "$SERIAL" shell "settings put global overlay_display_devices 1920x1080/320"
        echo "[6/9] 拉起 VRService（用动态 uid，不再写死 10073）..."
        U="$(uid_of com.huawei.vrservice)"
        [ -n "$U" ] && "$ADB" -s "$SERIAL" shell "su $U -c 'am start-service --user 0 -n com.huawei.vrservice/com.huawei.vrservice.VRService'" >/dev/null 2>&1
        echo "[7/9] 拉起 VrGlassWatchService -> MyService / VrSystemActor ..."
        U="$(uid_of com.huawei.hvrsdkserverapp)"
        [ -n "$U" ] && "$ADB" -s "$SERIAL" shell "su $U -c 'am start-service --user 0 -n com.huawei.hvrsdkserverapp/.compat.VrGlassWatchService'" >/dev/null 2>&1
        sleep 5
        echo "[8/9] 启动 VR 桌面入口（已验证为 .MainActivity）..."
        "$ADB" -s "$SERIAL" shell "am start -W -n com.huawei.vrlauncherx/.MainActivity" 2>&1 | tail -6
        echo "[9/9] 验收 ..."
        verify
        ;;
esac
