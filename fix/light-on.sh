#!/bin/sh
# =============================================================================
# 一键点亮华为 VR Glass（本地入口 / Git Bash、Linux、macOS 通用）
#
#   sh fix/light-on.sh                        # 默认 192.168.43.133:5555
#   sh fix/light-on.sh 192.168.43.133:5555   # 指定设备
#   sh fix/light-on.sh <serial> check        # 只体检
#   sh fix/light-on.sh <serial> off          # 清除强制分辨率属性（接便携屏前）
#
# 自包含：设备端没有 light-on.sh 时会自动 push 一份再执行，
# 所以刷机后不用先跑完整安装工具也能单独点亮。
# 逻辑全部在设备端 fix/magisk-module/runtime/light-on.sh，本脚本只负责转发。
# =============================================================================
set -u

SERIAL="${1:-192.168.43.133:5555}"
ACTION="${2:-on}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
    *MINGW*|*MSYS*|*CYGWIN*)
        command -v cygpath >/dev/null 2>&1 && HERE="$(cygpath -m "$HERE")" ;;
esac
SRC="$HERE/magisk-module/runtime/light-on.sh"
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
ADB="$(find_adb)" || { echo "[FATAL] 找不到 adb，请设置 ADB 环境变量"; exit 1; }

[ -f "$SRC" ] || { echo "[FATAL] 缺少 $SRC"; exit 1; }

echo "[i] adb    = $ADB"
echo "[i] 设备   = $SERIAL"
echo "[i] 动作   = $ACTION"
"$ADB" connect "$SERIAL" >/dev/null 2>&1
"$ADB" -s "$SERIAL" wait-for-device >/dev/null 2>&1 || {
    echo "[FATAL] 设备连不上（检查盒子是否开机、无线 adb 是否开启）"; exit 1; }

# 每次都同步一次（脚本只有几 KB），保证用的是本地最新母本
echo "[i] 同步点亮脚本到设备 ..."
"$ADB" -s "$SERIAL" push "$SRC" /data/local/tmp/light-on.sh >/dev/null 2>&1 || {
    echo "[FATAL] push 失败"; exit 1; }
"$ADB" -s "$SERIAL" shell "su -c 'mkdir -p /data/adb/hvrglass; cp /data/local/tmp/light-on.sh /data/adb/hvrglass/light-on.sh; chmod 755 /data/adb/hvrglass/light-on.sh'" >/dev/null 2>&1

echo
TMPOUT="${TMPDIR:-/tmp}/hvr-light-on.out"
"$ADB" -s "$SERIAL" shell "su -c 'sh /data/adb/hvrglass/light-on.sh $ACTION'" > "$TMPOUT" 2>&1
RC=$?
tr -d '\r' < "$TMPOUT"
echo
echo "[i] 设备端完整日志：/data/local/tmp/hvr-light-on.log"
exit $RC
