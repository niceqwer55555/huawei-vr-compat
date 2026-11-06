#!/system/bin/sh
#
# 让普通 app 能打开眼镜的 HID 通道（IMU 数据源）。
#
# 为什么需要：
#   眼镜在通用内核上暴露成标准 /dev/hidrawN（VID:PID = 12d1:1092 / 12d1:107e），
#   但 ueventd 给的权限是 system:audio 0660，普通 app（u0_aXX）打不开，
#   open() 直接 EACCES。ALVR 客户端因此拿不到 IMU，头追永远是单位四元数。
#
# 为什么用轮询而不是 chmod 一次：
#   眼镜会自己掉线重枚举（Type-C 重新协商、MCU 重启），每次重枚举都会
#   重新创建 hidraw 节点、权限被打回 0660。所以这里常驻守着。
#
LOG=/data/local/tmp/hidraw_perm.log
echo "$(date) service.sh 启动" >> "$LOG"

# --- 拉起华为 VR 服务链 ---
# 背景：VRService / ArvrRuntimeService 都只是普通 Service，没有开机广播；
#       华为原方案由系统服务 vr_system 拉起，本盒子缺这一环 —— 不手动起就是空的。
# 做法：后台等 boot_completed 再 start，不能阻塞下面的 hidraw 守护死循环。
(
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 90 ]; do
        i=$((i + 1))
        sleep 2
    done
    sleep 8
    export PATH=/system/bin:/system/xbin:$PATH
    am start-service -n com.huawei.vrservice/com.huawei.vrservice.VRService 2>/dev/null
    am start-service -n com.huawei.hvrsdkserverapp/com.huawei.hvrsdkserverapp.service.ArvrRuntimeService 2>/dev/null
    echo "$(date) 已请求拉起 VRService + ArvrRuntimeService" >> "$LOG"
) &

# --- 无线 adb 已移交独立 wadb 模块 ---
# 历史教训（2026-11-04 晚）：这里曾有第二个 adb 兜底循环，与 wadb 模块
# 的循环互相 kill adbd 打架，双方都等不到 5555 监听。adb 自启职责
# 全部归 wadb 模块（/data/adb/modules/wadb/service.sh），此处不再插手。

# --- 眼镜点亮自愈：已移除（2026-11-05 回滚）---
# 2026-11-04 晚曾在这里加过开机 15 秒后自动 setprop 强制分辨率 + detect 重探
# + `stop/start vendor.hwcomposer-2-1` 的"自愈"。它是上午成功之后新增的变量，
# HWC/SF 重启会让 system_server 图形会话僵死（Surface$OutOfResourcesException），
# 更早的驱动 unbind/rebind 版本还两次实证内核 panic 崩循环。
# 手册第六章的成功流程是最小集：真实 HWV EDID 直通 + 白名单 bind mount
# + 一次 `stop; start surfaceflinger`（手工、按需），没有后台自动强扭。
# 点亮问题应回到眼镜侧：手册 4.3 —— 眼镜有粘性激活态，失活后先用 PC/手机
# 点亮一次再插回盒子；EDID 指纹 22d2=转换器默认（眼镜没醒）、22f6=HWV 真实。

# --- vcom 节点兼容开关（2026-11-06）---
# 只有 /data/adb/hvrglass/ttyusb_compat 文件存在时才启用；默认关闭，
# 不改变既有行为。详见 fix/ttyusb-compat.sh 头部说明。
TTYUSB_REGISTERED=0

while true; do
    for h in /dev/hidraw*; do
        [ -e "$h" ] || continue
        # 只在权限不对时才 chmod，避免每轮都写
        perm=$(stat -c '%a' "$h" 2>/dev/null)
        if [ "$perm" != "666" ]; then
            chmod 666 "$h" 2>/dev/null && \
                echo "$(date) $h $perm -> 666" >> "$LOG"
        fi
    done
    # --- 6DoF 双目摄像头节点权限（2026-11-05 新增）---
    # 12d1:3a09 6DoF Gaming Kit 枚举后会建 /dev/video14、/dev/video15
    # （名字含 "6DoF"），ueventd 给的是 media:camera 0660，VR 服务是 u0_aXX
    # 普通 app 打不开。这里按节点名识别，编号随重枚举变化也能覆盖，
    # 不误伤系统自带的其它 camera 节点。
    # 注意：这只是第一道墙。Android 11 对「后台启动的前台服务」还有
    # camera 访问限制（见手册 6.8），需要由前台组件触发服务才能拿到。
    for v in /dev/video*; do
        [ -e "$v" ] || continue
        n=$(basename "$v")
        nm=$(cat "/sys/class/video4linux/$n/name" 2>/dev/null)
        case "$nm" in
            *6DoF*)
                perm=$(stat -c '%a' "$v" 2>/dev/null)
                if [ "$perm" != "666" ]; then
                    chmod 666 "$v" 2>/dev/null && \
                        echo "$(date) $v (6DoF) $perm -> 666" >> "$LOG"
                fi
                ;;
        esac
    done
    # --- /dev/hwvr1 符号链接维护（2026-11-04） ---
    # vrservice/WalleDev 硬编码打开 /dev/hwvr1；真机是华为内核 misc 设备，
    # 通用内核没有。touch 假文件=打开成功但协议全废；symlink 到眼镜 HID
    # （12d1:107e 的 hidraw）后 HIDIOCSFEATURE(report 5) 激活走通。
    # 眼镜重枚举会换 hidraw 编号吗？本机固定 hidraw1/2=HID、hidraw0=CV10，
    # 若编号变化按"最后一个非 hidraw0"处理也可，先保持实测有效的 hidraw1。
    if [ -e /dev/hidraw1 ] && [ ! -L /dev/hwvr1 ]; then
        rm -f /dev/hwvr1
        ln -s /dev/hidraw1 /dev/hwvr1 2>/dev/null && \
            echo "$(date) hwvr1 -> hidraw1" >> "$LOG"
    elif [ -L /dev/hwvr1 ] && [ ! -e /dev/hidraw1 ]; then
        # 眼镜拔出后 hidraw1 消失，摘掉死链，等插回再建
        rm -f /dev/hwvr1
        echo "$(date) hwvr1 死链清除（hidraw1 消失）" >> "$LOG"
    fi

    # --- /dev/ttyUSB* vcom 节点（2026-11-06 新增，受开关控制）---
    # 12d1:107e 的 3 个 vendor bulk 接口在通用内核里没有驱动绑定，
    # 因此永远不生成 /dev/ttyUSB0；VRService 的 UpgradeGlass.cpp 硬编码
    # open("/dev/ttyUSB0")，失败 5 次后放弃 -> heartbeat not init 死循环
    # -> proximity -1 -> 3D 桌面不启动。
    # 写 VID/PID 到 usbserial_generic 的 new_id 可让驱动接管并生成 ttyUSB0/1/2；
    # HID 接口无 bulk 端点会被驱动跳过，/dev/hwvr1 symlink 不受影响。
    # 眼镜 USB 每次重枚举都会重建节点并把权限打回 0660，故每轮校正。
    if [ -f /data/adb/hvrglass/ttyusb_compat ]; then
        if [ "$TTYUSB_REGISTERED" = "0" ] && \
           [ -w /sys/bus/usb-serial/drivers/generic/new_id ]; then
            echo "12d1 107e" > /sys/bus/usb-serial/drivers/generic/new_id 2>/dev/null &&
                TTYUSB_REGISTERED=1 &&
                echo "$(date) ttyusb_compat: new_id 已注册" >> "$LOG"
        fi
        for t in /dev/ttyUSB*; do
            [ -e "$t" ] || continue
            perm=$(stat -c '%a' "$t" 2>/dev/null)
            if [ "$perm" != "666" ]; then
                chmod 666 "$t" 2>/dev/null &&
                    echo "$(date) $t $perm -> 666" >> "$LOG"
            fi
        done
    fi
    sleep 3
done
