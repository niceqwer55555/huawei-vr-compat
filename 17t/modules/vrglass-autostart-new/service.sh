#!/system/bin/sh
# vrglass-autostart-new — 新系统线(Android11/OnePlus7T)插眼镜自动启动 VR 3D 桌面。
#
# 设计要点（吸取旧 vrglass-autostart 与新系统线的教训）：
#  1) 本模块只做「检测 + 调用现有 /data/local/tmp/start_vr_desktop.sh」，
#     不含任何 so/dex/APK，不碰眼镜画面方向，不碰充电节点/wm。
#  2) start_vr_desktop.sh 非幂等（会 force-stop 整条 VR 链再重启），
#     所以这里必须边沿触发：眼镜从无到有、或桌面不在 display1 时才跑，
#     且跑完进入 cooldown，绝不周期性重跑。
#  3) 眼镜控制口 = USB 12d1:107e，判据用 dumpsys usb 的十进制
#     vendor_id=4817/product_id=4222（新内核 sysfs /proc 读不到 idVendor）。
#  4) HDMI display 必须在位（dumpsys 里有 DisplayDeviceInfo{"HDMI...），
#     只有 USB 在位、DP 视频链路还没起来时也不启动。
#  5) 单实例心跳，防 Magisk 与手工重复拉起叠加。
#
# 日志：/data/local/tmp/vr_autostart.log  （回滚见文末）

MODDIR=${0%/*}
LOG=/data/local/tmp/vr_autostart.log
STATE=/data/local/tmp/.vr_autostart_present   # 眼镜在位状态: 1/0
LAST=/data/local/tmp/.vr_autostart_last       # 上次启动时间戳
HB="$MODDIR/.heartbeat"
START=/data/local/tmp/start_vr_desktop.sh
COOLDOWN=25   # 两次自动启动的最小间隔(秒)

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

# 单实例：12s 内有心跳就说明已有实例在跑
now=$(date +%s 2>/dev/null || echo 0)
last_hb=$(cat "$HB" 2>/dev/null || echo 0)
case "$last_hb" in ''|*[!0-9]*) last_hb=0 ;; esac
if [ $((now - last_hb)) -ge 0 ] && [ $((now - last_hb)) -lt 12 ]; then
  exit 0
fi

# 等开机完成 + VR 框架依赖（hvr_hidraw 的 /dev/hwvr1）就绪
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 8

# 眼镜控制口 12d1:107e 是否在位。
# 注意：新内核的 /sys/bus/usb/devices/*/idVendor 与 /proc/bus/usb/devices
# 都读不到（实测为空），唯一可靠来源是 dumpsys usb 里的十进制
# vendor_id=4817(0x12d1) / product_id=4222(0x107e)。
glass_present() {
  dumpsys usb 2>/dev/null | grep -q 'vendor_id=4817' && \
  dumpsys usb 2>/dev/null | grep -q 'product_id=4222'
}

# HDMI 头显 display 是否已枚举
hdmi_ready() {
  dumpsys display 2>/dev/null | grep -q 'DisplayDeviceInfo{"HDMI'
}

# VR 3D 桌面是否已在头显屏(MainActivity 作为某 display 的 resumed)
desktop_running() {
  dumpsys activity activities 2>/dev/null | grep -qE 'mResumedActivity.*com\.huawei\.vrlauncherx/\.MainActivity'
}

log "=== autostart daemon up (cooldown=${COOLDOWN}s) ==="

(
  exec >/dev/null 2>&1
  while :; do
    now=$(date +%s 2>/dev/null || echo 0)
    echo "$now" > "$HB" 2>/dev/null

    present=0
    glass_present && present=1
    prev=$(cat "$STATE" 2>/dev/null || echo 0)
    case "$prev" in ''|*[!0-9]*) prev=0 ;; esac

    if [ "$present" = "1" ] && [ "$prev" = "0" ]; then
      log "glass PLUGGED (12d1:107e appeared)"
    fi
    if [ "$present" = "0" ] && [ "$prev" = "1" ]; then
      log "glass UNPLUGGED"
    fi
    echo "$present" > "$STATE"

    # 触发条件：眼镜在位 + HDMI 就绪 + 桌面没在跑
    if [ "$present" = "1" ] && hdmi_ready && ! desktop_running; then
      last=$(cat "$LAST" 2>/dev/null || echo 0)
      case "$last" in ''|*[!0-9]*) last=0 ;; esac
      if [ $((now - last)) -ge "$COOLDOWN" ]; then
        if [ -f "$START" ]; then
          log "launching desktop via start_vr_desktop.sh"
          echo "$now" > "$LAST"
          # 同步执行：脚本内部会 force-stop 整条链再拉起，需等它跑完(~40s)
          sh "$START" >> "$LOG" 2>&1
          log "start_vr_desktop.sh returned"
          # 桌面起来后默认打开原版 ALVR 串流（可选：删掉下面 launch_alvr.sh 即禁用）
          if [ -f "$MODDIR/launch_alvr.sh" ]; then
            sleep 10
            # ALVR 启动会切走桌面 -> 刷新 LAST 防止守护把这次切换误判为"桌面掉了"
            # 而在 cooldown 内重复拉桌面(否则会形成 桌面->ALVR->桌面 循环)。
            echo "$(date +%s 2>/dev/null || echo 0)" > "$LAST"
            log "launching ALVR after desktop"
            sh "$MODDIR/launch_alvr.sh" >> "$LOG" 2>&1
            log "launch_alvr.sh returned"
            echo "$(date +%s 2>/dev/null || echo 0)" > "$LAST"
          fi
        else
          log "ERROR: $START missing"
        fi
      fi
    fi

    sleep 3
  done
) >/dev/null 2>&1 </dev/null &

exit 0

# ---- 回滚 ----
#   adb shell 'su -c "rm -rf /data/adb/modules/vrglass-autostart-new"'
# （不影响已启动的桌面；下次插眼镜不再自动启动）
