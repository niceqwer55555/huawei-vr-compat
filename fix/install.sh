#!/bin/sh
# =============================================================================
# install.sh —— 华为 VR 全栈一键安装（RK3566 盒子 / Android 11 / Magisk）
#
# 用法：
#   sh install.sh 192.168.43.133:5555              # 完整安装 + 一键点亮眼镜
#   sh install.sh 192.168.43.133:5555 --skip-install   # 只部署模块 + 授权（不装 APK）
#   sh install.sh 192.168.43.133:5555 --no-launch      # 完整安装但不启动应用市场
#   sh install.sh 192.168.43.133:5555 --no-light-on    # 装完不点亮（先用便携屏时）
#   sh install.sh 192.168.43.133:5555 --with-overlay   # 部署 system overlay（不建议，会卡 logo）
#
# 参数可任意顺序、可叠加。本脚本幂等，可反复执行。所有细节见 README.md。
# 眼镜点亮部分严格复刻手册第六章成功流程，见 light-on.sh 头部注释。
# =============================================================================
set -u

SERIAL="${1:-192.168.43.133:5555}"
MODE=full                 # full | --skip-install | --no-launch
WITH_OVERLAY=0            # system overlay 默认不装（卡 logo 元凶）
LIGHT_ON=1                # 装完默认执行一键点亮；眼镜没激活时脚本会自检跳过
[ "$#" -gt 0 ] && shift
for a in "$@"; do
    case "$a" in
        --skip-install|--no-launch|full) MODE="$a" ;;
        --with-overlay) WITH_OVERLAY=1 ;;
        --no-light-on)  LIGHT_ON=0 ;;
        --light-on)     LIGHT_ON=1 ;;
    esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"
# Git Bash / MSYS 下把路径转成 Windows 形式，否则 Windows adb.exe 吃不了 /f/... 路径
case "$(uname -s)" in
    *MINGW*|*MSYS*|*CYGWIN*)
        command -v cygpath >/dev/null 2>&1 && HERE="$(cygpath -m "$HERE")" ;;
esac

# ---- Git Bash 路径转换会破坏 adb 的远端路径，统一关掉 ----
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

# ---- 找 adb ----
find_adb() {
    if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then printf '%s' "$ADB"; return 0; fi
    if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
    for c in "/d/Tools/AndroidSDK/platform-tools/adb.exe" \
             "/c/platform-tools/adb.exe" "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
        [ -x "$c" ] && { printf '%s' "$c"; return 0; }
    done
    return 1
}

ADB="$(find_adb)" || { echo "[FATAL] 找不到 adb，请设置 ADB 环境变量"; exit 1; }
echo "[i] adb = $ADB"
echo "[i] 目标设备 = $SERIAL"

sh_()   { "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }
# root_()：优先 su -c；su 不可用（未授权/无 Magisk）但 adbd 本身是 root（adb root / 宽松固件）时直接明文执行
root_() {
  local out
  out=$("$ADB" -s "$SERIAL" shell "su -c 'id -u'" 2>/dev/null | tr -d '\r')
  if [ "$out" = "0" ]; then
    "$ADB" -s "$SERIAL" shell "su -c '$*'" 2>/dev/null | tr -d '\r'
  else
    "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'
  fi
}

step() { echo; echo "===== $* ====="; }

# =============================================================================
step "0/7 连接设备并体检"
"$ADB" connect "$SERIAL" >/dev/null 2>&1
"$ADB" -s "$SERIAL" wait-for-device || { echo "[FATAL] 设备连不上"; exit 1; }

DEV_MODEL=$(sh_ getprop ro.product.model)
DEV_VER=$(sh_ getprop ro.build.version.release)
DEV_ABI=$(sh_ getprop ro.product.cpu.abilist)
echo "  型号: $DEV_MODEL  Android: $DEV_VER"
echo "  ABI : $DEV_ABI"
echo "$DEV_ABI" | grep -q armeabi-v7a || echo "  [WARN] 无 32 位支持：除 vrservice 外的 6 个包装不上"

if root_ "id" | grep -q "uid=0"; then
    echo "  root: OK (su 可用)"
else
    echo "  [FATAL] 无 root（su 失败），模块和授权步骤无法执行"
    exit 1
fi

# =============================================================================
step "1/7 安装七个 APK（统一 hvr-compat 签名）"
if [ "$MODE" = "--skip-install" ]; then
    echo "  （--skip-install，跳过）"
else
    "$ADB" -s "$SERIAL" shell mkdir -p /data/local/tmp/hvrfix >/dev/null 2>&1
    install_one() {
        f="$1"; pkg="$(basename "$f" .apk)"
        out=$("$ADB" -s "$SERIAL" install -r -d "$f" 2>&1)
        if echo "$out" | grep -q "Success"; then
            echo "  OK     $pkg"
            return 0
        fi
        # 签名不一致（比如之前装过华为原签/旧签名包）→ 卸载重装
        if echo "$out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
            echo "  REINST $pkg（签名不一致，卸载重装，数据会丢）"
            "$ADB" -s "$SERIAL" uninstall "$pkg" >/dev/null 2>&1
            out=$("$ADB" -s "$SERIAL" install -r -d "$f" 2>&1)
            echo "$out" | grep -q "Success" && { echo "  OK     $pkg"; return 0; }
        fi
        echo "  FAIL   $pkg"
        echo "$out" | sed 's/^/         /'
        return 1
    }
    FAILED=0
    for f in "$HERE"/apks/*.apk; do
        # SKIP_PKGS：空格分隔的包名文件名片段（不带 .apk），命中则跳过——分阶段排查用
        base="$(basename "$f" .apk)"
        skip=0
        for p in ${SKIP_PKGS:-}; do
            case "$base" in *"$p"*) skip=1; break ;; esac
        done
        [ "$skip" = 1 ] && { echo "  SKIP   $base（SKIP_PKGS）"; continue; }
        install_one "$f" || FAILED=1
    done
    [ "$FAILED" = 0 ] || echo "  [WARN] 有包没装上，见上方输出，后续步骤照常执行"
fi

# =============================================================================
step "2/7 部署 hvrglass Magisk 模块（EDID 按需注入 + hidraw 权限）"
"$ADB" -s "$SERIAL" shell mkdir -p /data/local/tmp/hvrfix >/dev/null 2>&1
"$ADB" -s "$SERIAL" push "$HERE/magisk-module" /data/local/tmp/hvrfix/ >/dev/null 2>&1 \
    || "$ADB" -s "$SERIAL" push "$HERE/magisk-module/." /data/local/tmp/hvrfix/magisk-module/ >/dev/null 2>&1

root_ "mkdir -p /data/adb/hvrglass
cp -rf /data/local/tmp/hvrfix/magisk-module/modules/. /data/adb/modules/hvrglass/
cp -f  /data/local/tmp/hvrfix/magisk-module/runtime/edid-mode.sh    /data/adb/hvrglass/
cp -f  /data/local/tmp/hvrfix/magisk-module/runtime/edid_1080p.bin  /data/adb/hvrglass/
cp -f  /data/local/tmp/hvrfix/magisk-module/runtime/resolution_white.backup.xml /data/adb/hvrglass/
# --- 一键点亮脚本（手册第六章成功流程，刷机/眼镜失活后单独跑 fix/light-on.sh 即可）---
cp -f  /data/local/tmp/hvrfix/magisk-module/runtime/light-on.sh     /data/adb/hvrglass/
# --- 眼镜点亮关键：3200x1600 白名单（模块 post-fs-data.sh 开机 bind mount 到
#     /system/usr/share/resolution_white.xml，HWC 的 mode_verify 只放行白名单内
#     的模式；原厂白名单没有 3200x1600，眼镜（无 scaler）必黑屏。条目值必须是
#     内核解析真值（tools/modeinfo.c 读出的），手算必错 —— 见恢复手册 6.1 ---
cp -f  /data/local/tmp/hvrfix/magisk-module/modules/resolution_white.xml /data/adb/hvrglass/resolution_white.xml
[ -f /data/adb/hvrglass/mode ] || echo auto > /data/adb/hvrglass/mode
chmod 755 /data/adb/modules/hvrglass /data/adb/modules/hvrglass/post-fs-data.sh /data/adb/modules/hvrglass/service.sh /data/adb/hvrglass/edid-mode.sh /data/adb/hvrglass/light-on.sh
chmod 644 /data/adb/modules/hvrglass/module.prop /data/adb/modules/hvrglass/common/edid_only.bin /data/adb/hvrglass/edid_1080p.bin /data/adb/hvrglass/resolution_white.xml /data/adb/hvrglass/mode" >/dev/null 2>&1

# --- 可选 system overlay（public.libraries.txt 替换 + libc_secshared.so）---
# 仅当第三个参数 --with-overlay 才部署。2026-11-04 两次卡 logo 时它都在场，
# 默认不装（vrservice 私有 lib 自带所需 so，多数情况不再需要它）。
if [ "$WITH_OVERLAY" = "1" ]; then
    root_ "mkdir -p /data/adb/modules/hvrglass/system/etc /data/adb/modules/hvrglass/system/lib64
cp -f /data/local/tmp/hvrfix/magisk-module/overlay-optional/etc/public.libraries.txt /data/adb/modules/hvrglass/system/etc/
cp -f /data/local/tmp/hvrfix/magisk-module/overlay-optional/lib64/libc_secshared.so /data/adb/modules/hvrglass/system/lib64/
chmod 644 /data/adb/modules/hvrglass/system/etc/public.libraries.txt /data/adb/modules/hvrglass/system/lib64/libc_secshared.so" >/dev/null 2>&1
    echo "  [i] system overlay 已部署（--with-overlay）"
else
    echo "  [i] system overlay 未部署（默认；卡 logo 排查期间保持关闭）"
fi
root_ "rm -rf /data/local/tmp/hvrfix/magisk-module" >/dev/null 2>&1

echo "  模块: $(root_ 'ls /data/adb/modules/hvrglass/module.prop && echo OK')"
echo "  显示当前内核输出时序："
root_ "sh /data/adb/hvrglass/edid-mode.sh show" | sed 's/^/    /'
echo "  [i] 眼镜不亮 → 一键点亮: sh fix/light-on.sh  /  双击 fix/一键点亮.bat"
echo "      设备端等价: su -c 'sh /data/adb/hvrglass/light-on.sh'"
echo "  [i] 便携屏黑屏时执行: su -c 'sh /data/adb/hvrglass/edid-mode.sh 1080p'"
echo "  [i] 别用 force-vr 固化、别在开机阶段注入 EDID（手册 4.1：会卡 logo）"

# =============================================================================
step "3/7 绕过 launcherx 隐私页（Android 11 禁写 Settings.System 自定义键）"
root_ "settings put system PRIVACY_ACCEPTED 1" >/dev/null 2>&1
echo "  PRIVACY_ACCEPTED = $(sh_ 'settings get system PRIVACY_ACCEPTED')"

# =============================================================================
step "4/7 补授权（appops / pm grant / deviceidle / 日志级别）"
sh "$HERE/tools/grant-vr-perms.sh" "$SERIAL" 2>/dev/null || \
    echo "  [WARN] grant-vr-perms.sh 执行异常，可手动重跑"

# --- 恢复模块启用 + 平台伪装 + VR 服务链（都是历史实证的既有配置，非新增开机逻辑）---
# ① rescue_boot.sh --disable-hvrglass 救砖后会留下 disable 标记。若不删，
#    模块整套不跑：无 HUAWEI 伪装、无白名单 bind mount、无 hidraw 权限守护、
#    也无 VR 服务开机自启 —— 表现就是「VR 服务全空 / 投屏弹不支持 / 眼镜不亮」。
# ② post-fs-data.sh 的 resetprop 只在开机执行，本次安装不重启，故运行时补一次；
#    模块启用后下次开机会自动再设。
root_ "rm -f /data/adb/modules/hvrglass/disable" >/dev/null 2>&1
echo "  hvrglass 模块 = $(sh_ 'test -f /data/adb/modules/hvrglass/disable && echo DISABLED || echo enabled')"
root_ "resetprop ro.product.manufacturer HUAWEI; resetprop ro.vr.surport true" >/dev/null 2>&1
echo "  ro.vr.surport = $(sh_ 'getprop ro.vr.surport')"
root_ "am start-service -n com.huawei.vrservice/com.huawei.vrservice.VRService; am start-service -n com.huawei.hvrsdkserverapp/com.huawei.hvrsdkserverapp.service.ArvrRuntimeService" >/dev/null 2>&1
echo "  VR 服务进程："
sh_ "ps -A | grep -E 'vrservice|ArvrRuntimeService' | grep -v grep" | sed 's/^/    /' || \
    echo "    （未起，检查 deviceidle 白名单是否含 vrservice / hvrsdkserverapp）"

# =============================================================================
step "5/7 一键点亮 VR 眼镜（严格按手册第六章成功流程）"
# light-on.sh 内部先自检：眼镜 EDID 必须醒着（模式表含 3200x1600），
# 否则直接拒绝、不写属性也不重启 SF —— 眼镜失活时盒子单方面强扭没用。
# 注意：绝不用 setprop ctl.restart surfaceflinger（手册 4.2 实测整机重启/卡死）。
if [ "$LIGHT_ON" = "1" ]; then
    root_ "sh /data/adb/hvrglass/light-on.sh" | sed 's/^/  /'
    # SF 重启后重连一次，保证后面的验收与启动步骤可用
    sleep 3
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
else
    echo "  （--no-light-on，跳过。随时可跑: sh fix/light-on.sh 或双击 fix/一键点亮.bat）"
fi

# =============================================================================
step "6/7 状态验收"
echo "-- 已安装的华为 VR 包 --"
sh_ "pm list packages" | grep -E 'com\.huawei\.(appmarket\.vr|vrlauncherx|himovie\.vr|vrvirtualscreen|vrhandle|hvrsdkserverapp|vrservice)' | sed 's/^/  /'
echo "-- 关键进程 --"
sh_ "ps -A | grep -E 'vrservice|vrvirtualscreen|vrhandle|himovie|hvrsdkserverapp|vrlauncherx|appmarket' | grep -v grep" | sed 's/^/  /' || echo "  （应用尚未启动，属正常）"
echo "-- vr_system 服务（目前仍缺，属已知问题，非安装失败）--"
if sh_ "service list" | grep -q vr_system; then
    echo "  vr_system 已注册！（意外之喜，说明系统侧桩已生效）"
else
    echo "  vr_system 未注册 —— 需 LSPosed 在 system_server 注册桩，或走 ALVR 路线（见 README）"
fi

# =============================================================================
step "7/7 启动验证"
if [ "$MODE" = "--no-launch" ]; then
    echo "  （--no-launch，跳过）"
else
    "$ADB" -s "$SERIAL" shell am start -W -n \
        com.huawei.appmarket.vr/com.huawei.hwmarket.vr.MainActivity 2>/dev/null | tr -d '\r' | grep -E 'Status|TotalTime|Error' | sed 's/^/  /'
fi

echo
echo "===== 完成 ====="
echo "  眼镜没亮 / 之后又黑：sh fix/light-on.sh 或双击 fix/一键点亮.bat"
echo "  只体检不改动：      sh fix/light-on.sh 192.168.43.133:5555 check"
echo "  接便携屏前：        sh fix/light-on.sh 192.168.43.133:5555 off"
echo "  细节与排障见 README.md / 刷机后问题分析与恢复手册.md 6.6"
