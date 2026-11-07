#!/bin/sh
# vr-display-check.sh —— 只读体检「VR 桌面里打开的应用，为什么没在桌面里显示」
#
# 用法：
#   sh fix/tools/vr-display-check.sh [serial]   # 默认 192.168.43.133:5555
#
# 背景（2026-11-08 实测 + 反编译，详见 fix/README.md 11.12 / 分析文档第十二章）：
#   VR 桌面是 Unity 3D 场景，2D 应用**不会**因为「进程起来了」就自动出现在眼镜里。
#   兼容层复刻的是 EMUI 原厂三段式显示链（docs/PROJECT_STATUS.md 第 8/9 条、
#   docs/PATCH_AUDIT.md 65.3）：
#     ① HvrVrPanelPresenter 在眼镜副屏上起 Presentation + SurfaceView
#     ② HvrVrVirtualDisplay.ensure() 建 3200x1600 的 `HUAWEI VR Display` 虚拟屏
#     ③ HvrDisplayRouter.applyVrDisplay() 用 ActivityOptions.setLaunchDisplayId
#        把应用 Activity 路由到该虚拟屏 → 画面合成进 Presentation → 出现在眼镜里
#   任何一环缺了，应用都只会落在默认 displayId=0（主屏），VR 桌面里看不到。
#   另外平台只允许「可信屏」承载别处启动的 Activity，普通应用建的虚拟屏
#   isTrusted()=false，需要 LSPosed 模块 com.hvr.displayrouting hook 放行。
#
# 本脚本只读：不改设置、不重启服务、不发任何 USB 请求。
set -u

SERIAL="${1:-192.168.43.133:5555}"
ADB="${ADB:-D:/Tools/AndroidSDK/platform-tools/adb.exe}"

"$ADB" connect "$SERIAL" >/dev/null 2>&1
sh_shell() { "$ADB" -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }
say() { printf '\n=== %s ===\n' "$1"; }

say "A. Display 全景（设备实际有哪些屏）"
sh_shell 'dumpsys display | grep -o "DisplayDeviceInfo{[^,]*, [0-9]* x [0-9]*[^,]*" | head -12'
echo "    （HW-VR-Cast-* = 投屏双目镜像屏，尺寸由 overlay 副屏推算，见 C）"
echo "--- HUAWEI VR Display 计数（>0 才算三段式第②环通）---"
VRD=$(sh_shell 'dumpsys display | grep -c "HUAWEI VR Display"')
echo "    HUAWEI VR Display = ${VRD:-0}   （期望 ≥1，尺寸 3200x1600）"

say "B. 各 display 上的 Activity 栈（应用窗口到底在哪块屏）"
sh_shell 'dumpsys window displays | grep -E "^ *Display: mDisplayId=|mCurrentFocus=" | head -20'
echo "--- 当前栈顶 ---"
sh_shell 'dumpsys activity activities | grep mResumedActivity | head -1'

say "C. overlay_display_devices 与每眼尺寸推算"
OVR=$(sh_shell 'settings get global overlay_display_devices')
echo "    overlay_display_devices = ${OVR:-（未设置）}"
echo "    HvrMirrorService 公式: scale = min((副屏宽/2)/1920, 副屏高/1080)"
echo "                           eye = (round(1920*scale), round(1080*scale))"
W=$(echo "${OVR:-0x0}" | cut -d/ -f1 | cut -dx -f1)
H=$(echo "${OVR:-0x0}" | cut -d/ -f1 | cut -dx -f2)
echo "${W:-0} ${H:-0}" | awk '{ w=$1; h=$2; s=(w/2)/1920; t=h/1080; if(t<s)s=t; printf "    副屏 %dx%d → 每眼约 %dx%d\n",w,h,int(1920*s+0.5),int(1080*s+0.5) }'
echo "    （手册 7.4 成功态：overlay=1920x1080/320，双目虚拟屏 608x1080；"
echo "      100x100/320 是后来为消除左眼叠影压小的，副作用是把面板压成空壳）"

say "D. 显示路由日志（当前 logcat 缓冲区）"
for k in "HvrDisplayRouter: HUAWEI VR Display not up yet" "vr activity routed to display" "HvrVrPanel" "mirror overlay shown on display" "HvrDisplayRouting"; do
    n=$(sh_shell "logcat -d | grep -Fc '$k'")
    printf "    %-52s %s\n" "$k" "${n:-0}"
done
echo "    （'not up yet' > 0 = ③ 每次都放弃路由，应用留在主屏；"
echo "      HvrVrPanel = 0 说明 ① 的 Presentation 从未成功挂上）"
echo "    注意：缓冲区会滚动，历史证据在 build/tmp/analysis-1108-*.log 里 grep。"

say "E. LSPosed / 显示路由模块（③ 跨应用放行的前置）"
printf "    LSPosed 包 : "
LP=$(sh_shell 'pm list packages | grep -Ei "lsposed|riru|zygisk"')
echo "${LP:-（无）}"
printf "    已装模块   : "
MOD=$(sh_shell 'su -c "ls -1 /data/adb/modules/"')
echo "${MOD:-（无）}" | tr '\n' ' '; echo
printf "    lspd 目录  : "
LSPD=$(sh_shell 'su -c "ls -1 /data/adb/lspd/ 2>/dev/null | head -3"')
echo "${LSPD:-（无）}" | tr '\n' ' '; echo
echo "    期望：LSPosed 已安装 + 模块 com.hvr.displayrouting 已启用且作用域含 android"
echo "    模块 APK 在仓库 lsposed/out/com.hvr.displayrouting-r*.apk"

say "F. 眼镜 USB 在位（全部前置的硬件基础）"
sh_shell 'echo -n "    hidraw 节点数 = "; ls /dev/hidraw* 2>/dev/null | wc -l; echo -n "    vr_system_state = "; timeout 4 content call --uri content://com.huawei.vrlauncherx.registry --method getService --arg vr_system_state 2>/dev/null | head -1'

say "结论速查"
if [ "${VRD:-0}" -ge 1 ]; then
    echo "  [OK] HUAWEI VR Display 已创建 —— 检查应用是否被 setLaunchDisplayId 路由过去"
else
    echo "  [缺②] HUAWEI VR Display 不存在 → HvrDisplayRouter 必然打印 'not up yet'，"
    echo "         应用 Activity 全部留在 displayId=0，VR 桌面的 3D 场景里不会出现它。"
    echo "         先让 HvrVrPanelPresenter.start() 跑起来（副屏 + Presentation），"
    echo "         再按 docs/PROJECT_STATUS.md 第 9 条启用 LSPosed 显示路由模块。"
fi
echo "  注意：应用本身通常是好的（am start 能起来并驻留），「没显示」是显示承载链的问题。"
echo "  应急直接开应用：sh fix/tools/open-vr-app.sh $SERIAL alvr"
