#!/bin/sh
# vr-desktop-view-check.sh —— 只读体检「VR 桌面能进，但眼镜里画面不对」
#
# 用法：
#   sh fix/tools/vr-desktop-view-check.sh [serial]      # 默认 192.168.43.133:5555
#   ADB=/path/to/adb sh fix/tools/vr-desktop-view-check.sh <serial>
#
# 场景（与 vr-display-check.sh 的分工）：
#   * vr-display-check.sh  查「VR 桌面里打开的**应用**为什么没显示」（三段式承载链）
#   * 本脚本              查「**VR 桌面自己**能进但画面变形/竖屏/半幅/偏移」——几何链
#
# 几何链自上而下五环（任何一环错，画面都不对）：
#   A 手机方向   user_rotation=1 + accelerometer_rotation=0（横屏锁定）
#   B 手机逻辑尺寸 wm size = 1600x3200（转横屏 3200x1600，与面板 1:1）
#                —— 缺这条就是 1080x2640→2640x1080，缩放 1.2121 + 139px 偏移
#                （PATCH_AUDIT §11.4 的历史现象）
#   C 面板 mode  眼镜外接屏物理 mode = 3200x1600@70/90，不是 1920x1080p60
#   D 虚拟屏     HUAWEI VR Display 已建、尺寸 3200x1600
#   E 路由       桌面 Activity 的层 bounds 与眼镜那路 layerstack 对齐
#
# 全部只读：不改设置、不重建屏、不发 USB 请求。
set -u

SERIAL="${1:-192.168.43.133:5555}"
if [ -n "${ADB:-}" ]; then
    ADB_BIN="$ADB"
elif command -v adb >/dev/null 2>&1; then
    ADB_BIN=adb
else
    ADB_BIN="D:/Tools/AndroidSDK/platform-tools/adb.exe"
fi

"$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1
sh_shell() { "$ADB_BIN" -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }
say() { printf '\n=== %s ===\n' "$1"; }
pf() { printf '    %-42s %s\n' "$1" "$2"; }

say "A. 手机方向（决定桌面内容是横是竖）"
ACC=$(sh_shell 'settings get system accelerometer_rotation')
ROT=$(sh_shell 'settings get system user_rotation')
IGN=$(sh_shell 'wm get-ignore-orientation-request')
pf "accelerometer_rotation" "${ACC:-?}  （期望 0=关闭自动旋转）"
pf "user_rotation" "${ROT:-?}  （期望 1=ROTATION_90 横屏）"
pf "ignore-orientation-request" "${IGN:-?}  （期望 false；true 会把应用锁在旧方向）"
if [ "$ACC" = "0" ] && [ "$ROT" = "1" ]; then
    echo "    [OK] A 横屏锁定在位"
else
    echo "    [FAIL] A 未锁横屏：桌面内容竖屏塞进横屏面板 → 左右眼错位/半幅"
    echo "           修：VrSystemActor 在眼镜在位时应写这两项；见 PATCH_AUDIT §11.5/§63"
fi

say "B. 手机逻辑尺寸（缩放比与 139px 偏移的来源）"
SZ=$(sh_shell 'wm size')
echo "$SZ" | sed 's/^/    /'
if echo "$SZ" | grep -q "Override size: 1600x3200"; then
    echo "    [OK] B 逻辑尺寸 1600x3200 → 转横屏 3200x1600，与面板 1:1"
elif echo "$SZ" | grep -q "Override size:"; then
    echo "    [WARN] B 有覆盖但不是 1600x3200 → 缩放比≠1，画面会被拉伸"
    echo "           修：wm size 1600x3200；见 ksu/hwvr_compat/service.sh set_size()"
else
    PHYS=$(echo "$SZ" | grep -o 'Physical size: .*' | cut -d' ' -f3-)
    echo "    [FAIL] B 无覆盖（物理 ${PHYS:-?}）：横屏 = $(echo "$PHYS" | awk -F'x' '{print $2"x"$1}')"
    echo "           这就是 2640x1080 + 缩放 1.2121 + 139px 偏移的旧现象（PATCH_AUDIT §11.4）"
    echo "           修：KSU 模块 service.sh 的 set_size() 在眼镜在位时设 wm size 1600x3200"
fi
WD=$(sh_shell 'dumpsys window displays | grep -E "cur=|app=" | head -2')
echo "    --- dumpsys window（当前/应用尺寸）---"
echo "$WD" | sed 's/^/      /'

say "C. 眼镜外接屏物理 mode（面板是否锁在 3200x1600）"
# 高通 DP Alt Mode 没有 RK 的 /sys/kernel/debug/dri summary，走 dumpsys display
EXT=$(sh_shell 'dumpsys display | grep -iE "HUAWEIAV03|HVR GLASS|HUAWEI VR" | head -6')
if [ -n "$EXT" ]; then
    echo "$EXT" | sed 's/^/    /'
else
    echo "    （未找到已知眼镜屏名，列出全部外接 DisplayDeviceInfo：）"
    sh_shell 'dumpsys display | grep -o "DisplayDeviceInfo{[^}]*EXTERNAL[^}]*" | head -4' | sed 's/^/    /'
fi
echo "    --- 外接屏 mode / 刷新率 ---"
sh_shell 'dumpsys display | grep -iE "mBaseDisplayInfo|modeId|refreshRate|supportedModes|3200 x 1600|1920 x 1080" | head -12' | sed 's/^/    /'
echo "    判据：眼镜屏须为 3200x1600（@70/90）。若仍是 1920x1080p60，"
echo "          虚拟屏 3200x1600 会被面板端拉伸 → 整幅画面变形（RK3566 §7.2 同类问题）。"
echo "          高通侧由 hotdogb-boot 的 DP 4-lane 补丁 + 内核/HWC 决定，compat 层改不了 mode。"

say "D. HUAWEI VR Display 虚拟屏（桌面画面的承载）"
VRD=$(sh_shell 'dumpsys display | grep -c "DisplayDeviceInfo{\"HUAWEI VR Display\""')
pf "HUAWEI VR Display 块数" "${VRD:-0}  （期望 1）"
sh_shell 'dumpsys display | grep -o "DisplayDeviceInfo{\"HUAWEI VR Display\"[^}]*}" | head -1' | sed 's/^/    /'
for k in "HvrVrDisplay: HUAWEI VR Display created" "HvrVrPanel: panel presentation shown" "HvrVrPanel: panel surface created"; do
    n=$(sh_shell "logcat -d | grep -Fc '$k'")
    pf "$k" "${n:-0}"
done
if [ "${VRD:-0}" -ge 1 ]; then
    echo "    [OK] D 虚拟屏在位"
else
    echo "    [FAIL] D 虚拟屏缺失 → 桌面可能直接画在默认屏，几何不受控"
fi

say "E. 桌面画面层的几何（SurfaceFlinger）"
echo "    --- vrlauncherx 层的 bounds / transform / layerstack ---"
sh_shell 'dumpsys SurfaceFlinger | grep -iE "vrlauncherx|HUAWEI VR Display" | head -8' | sed 's/^/    /'
sh_shell 'dumpsys SurfaceFlinger | grep -E "bounds=|toDisplayTransform|layerstack" | head -12' | sed 's/^/    /'
echo "    判据：桌面层 bounds 应为 3200x1600（横屏），不是 1600x3200 + tx≈139（竖屏旧现象）。"

say "========== 结论速查（按 likelihood 排序）=========="
P_C=0
echo "$EXT" | grep -q "3200" || P_C=1
if [ "$P_C" = "1" ]; then
    echo "  [1] 面板 mode：没读到 3200x1600 → 优先确认 DP Alt Mode 4-lane + 内核补丁"
    echo "      （这是 compat 层之外的一环，先排除它再改软件）"
fi
if ! { [ "$ACC" = "0" ] && [ "$ROT" = "1" ]; }; then
    echo "  [2] 方向：user_rotation/accelerometer_rotation 没锁 → 桌面竖屏/半幅"
fi
if ! echo "$SZ" | grep -q "Override size: 1600x3200"; then
    echo "  [3] 逻辑尺寸：wm size 非 1600x3200 → 缩放比≠1、139px 偏移"
fi
if [ "${VRD:-0}" -lt 1 ]; then
    echo "  [4] 虚拟屏：HUAWEI VR Display 没建 → 跑 vr-display-check.sh 查承载链"
fi
echo "  参考：PATCH_AUDIT §11.4/§11.5（几何与横屏）、§63（方向还原）、"
echo "        docs/SBS-VERIFY.md（三级判据）、RK3566-部署记录 §7.2（面板拉伸）"
echo "  只读体检完成（未改任何设备状态）"
