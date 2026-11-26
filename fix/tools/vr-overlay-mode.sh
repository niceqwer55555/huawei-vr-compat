#!/bin/sh
# vr-overlay-mode.sh —— 切换副屏（overlay_display_devices）尺寸档位
#
# 背景（2026-11-08，分析文档第十二章 12.4 / 第十三章；2026-11-26 修正每眼口径）：
#   同一块 overlay 副屏被两件事共用，目标互相冲突：
#     A. HvrVrPanelPresenter 在副屏上起 Presentation + SurfaceView，
#        再把 3200x1600 的 `HUAWEI VR Display` 输出进去（需要面积够大）；
#     B. HvrMirrorService 把这块副屏从中间一分为二当左右眼：
#          eyeWidth = panelWidth / 2,  eyeHeight = panelHeight
#          手机画面按 min(eyeWidth/phoneW, eyeHeight/phoneH) 缩放后居中放进每眼。
#
#   眼镜物理面板固定 3200x1600（2:1 横屏），SBS 左右并排 → 每眼 1600x1600（1:1 正方形）。
#   所以承载 SBS 的 overlay 面板必须设成横屏 3200x1600/320，HvrMirrorService 的每眼
#   SurfaceView 才正好是 1600x1600，合成到物理面板时 1:1 对齐、不变形。
#   旧实现把大屏设成 1920x1080 并按 16:9 缩放推算「每眼 960x540 / 608x1080」，
#   这是把 overlay 副屏尺寸误当成物理面板尺寸，口径错误（2026-11-26 已纠正）。
#
#   第九章为消除「overlay 是主屏缩小镜像、叠在主屏合成里递归叠影」把副屏
#   压到 100x100/320 -> 每眼格子仅 50x100，SBS 面板成了空壳（stacks=0）。
#
# 本脚本只改 settings global overlay_display_devices，不刷机、不改 APK。
# 修改会触发 DisplayManager 拆掉重建 overlay display，当前 Presentation
# 会 dismiss、虚拟屏随之释放，需重启 VR 桌面进程重新走 panel-bootstrap。
#
# 用法（默认 show，只读）：
#   sh fix/tools/vr-overlay-mode.sh [serial] show
#   sh fix/tools/vr-overlay-mode.sh [serial] small            # 100x100/320，消叠影
#   sh fix/tools/vr-overlay-mode.sh [serial] panel            # 3200x1600/320，SBS 每眼 1600x1600
#   sh fix/tools/vr-overlay-mode.sh [serial] dual             # 100x100 + 3200x1600 两块
#   sh fix/tools/vr-overlay-mode.sh [serial] set '800x600/320'
#   sh fix/tools/vr-overlay-mode.sh [serial] restore          # 恢复修改前的值
#
# 注意：dual 提供「两块屏职责分开」的硬件前提（小屏满足在位判据、大屏承载 SBS）。
#   2026-11-26 起，面板选择已按可视面积取最大：VrPlatformCompat.externalDisplay()
#   与 HvrPanelFinder 的各级判据都改为 largestDisplay()，因此 3200x1600 大屏会被选为
#   面板，不再退回 100x100 小屏。前提是 APK 用含该修复的兼容层重新构建（见
#   docs/SBS-PANEL-FIX.md）。旧 APK 不含此修复时 dual 仍会命中小屏。
set -u

SERIAL="${1:-192.168.43.133:5555}"
MODE="${2:-show}"
ADB="${ADB:-D:/Tools/AndroidSDK/platform-tools/adb.exe}"
BAK="/data/local/tmp/hvr_overlay_display_devices.bak"
KEY="overlay_display_devices"

"$ADB" connect "$SERIAL" >/dev/null 2>&1
sh_shell() { MSYS2_ARG_CONV_EXCL='*' "$ADB" -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }

cur() { sh_shell "settings get global $KEY"; }

eye_of() {
    # $1 = WxH/DPI[;WxH/DPI...]
    # 面积优先选中的那块作为 SBS 承载面板。眼镜物理面板固定 3200x1600，
    # SBS 每眼 = 面板宽/2 x 面板高 = 1600x1600（1:1）。overlay 副屏尺寸只决定
    # 选哪块当 panel display / HvrMirrorService 每眼 SurfaceView 的摆放大小，
    # 不改变物理 SBS 每眼 1600x1600 这一事实。
    printf '%s' "$1" | tr ';' '\n' | awk -F'[/x]' '
        $1+0>0 && $2+0>0 { a=$1*$2; if (a>best) { best=a; bw=$1+0; bh=$2+0 } }
        END {
            if (best<=0) { printf "无有效副屏"; exit }
            printf "按面积选 %dx%d 作 panel display；眼镜面板 3200x1600 → SBS 每眼 1600x1600", bw, bh
        }'
}

show() {
    v=$(cur)
    echo "=== overlay 副屏现状 ==="
    echo "  $KEY = ${v:-（未设置）}"
    [ "${v:-null}" = "null" ] || echo "  $(eye_of "$v")"
    echo
    echo "  HUAWEI VR Display : $(sh_shell 'dumpsys display | grep -c "DisplayDeviceInfo{\"HUAWEI VR Display\""') 块"
    sh_shell 'dumpsys display | grep -o "DisplayDeviceInfo{\"[^\"]*\": uniqueId=\"[^\"]*\", [0-9]* x [0-9]*" | head -8' | sed 's/^/    /'
    echo
    echo "  眼镜在位: hidraw=$(sh_shell 'ls /dev/hidraw* 2>/dev/null | wc -l')  /dev/hwvr1=$(sh_shell 'test -e /dev/hwvr1 && echo yes || echo no')"
    echo "  备份文件: $(sh_shell "test -f $BAK && cat $BAK || echo '（无）'")"
}

apply() {
    spec="$1"
    old=$(cur)
    if [ -z "$(sh_shell "ls $BAK 2>/dev/null")" ]; then
        sh_shell "settings get global $KEY > $BAK; chmod 644 $BAK" >/dev/null
    fi
    sh_shell "settings put global $KEY '$spec'" >/dev/null
    new=$(cur)
    echo "[+] $KEY: ${old:-（未设置）}  ->  $new"
    echo "    $(eye_of "$new")"
    echo "    原值已备份到设备 $BAK（restore 可回滚）"
    echo "    注意：overlay 重建会 dismiss 当前 Presentation 并释放虚拟屏，"
    echo "          需重启 VR 桌面让 panel-bootstrap 重新建屏。"
    echo "    若 3200x1600 虚拟屏创建失败，可用 set 回退到 1920x1080/320（旧口径，每眼会被拉伸）。"
}

case "$MODE" in
    show)
        show
        echo
        echo "档位说明：small=消左眼叠影；panel=横屏 3200x1600，SBS 每眼 1600x1600；"
        echo "          dual=小屏满足在位 + 3200x1600 当面板（需含 2026-11-26 面积优先修复的 APK）"
        ;;
    small)   apply "100x100/320" ;;
    panel)   apply "3200x1600/320" ;;
    dual)    apply "100x100/320;3200x1600/320" ;;
    set)     apply "${3:-100x100/320}" ;;
    restore)
        if [ -z "$(sh_shell "ls $BAK 2>/dev/null")" ]; then
            echo "[!] 设备上没有备份文件 $BAK，无法回滚"; exit 1
        fi
        old=$(sh_shell "cat $BAK")
        if [ "${old:-null}" = "null" ]; then
            sh_shell "settings delete global $KEY" >/dev/null
            echo "[+] 已删除 $KEY（恢复为未设置）"
        else
            apply "$old"
        fi
        ;;
    *)
        echo "用法: sh fix/tools/vr-overlay-mode.sh [serial] {show|small|panel|dual|set SPEC|restore}"
        exit 1
        ;;
esac
