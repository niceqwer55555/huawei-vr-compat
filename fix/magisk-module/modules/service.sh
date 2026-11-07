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
    # 手柄自动扫描（2026-11-07）：VRHandleService 没有开机广播，平时靠
    # VrSystemActor 每 20 秒的 ACTION_RECONNECT_MSG 拉起。这里显式发一次，
    # 让开机立即触发 AutoScanCompat 的 BLE 扫描，不用等首轮心跳。
    # 注意它只是「加速」：即使被 Android 11 后台启动限制拦下，心跳也会拉起。
    am start-foreground-service -n com.huawei.vrhandle/.service.VRHandleService -a com.huawei.vrservice.ACTION_RECONNECT_MSG 2>/dev/null
    echo "$(date) 已请求拉起 VRService + ArvrRuntimeService + VRHandleService" >> "$LOG"
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
    # --- /dev/hwvr1 控制接口维护（2026-11-07 改为按描述符探测）---
    # vrservice/WalleDev 硬编码打开 /dev/hwvr1；眼镜的 HIDIOCSFEATURE(5)
    # 激活命令必须发到「控制接口」才能点亮面板。
    #
    # ⚠️ 不能写死 hidraw 编号：2026-11-07 实测用户拔插眼镜后 CV10 那个
    # hidraw 消失，编号整体前移，真控制接口从 hidraw1 变成 hidraw0，
    # 而 hidraw1 变成 Consumer Control 按键口。旧逻辑仍指向 hidraw1，
    # 结果 DevHid "No Glass data" 723 次、hidraw "too short report"、
    # 激活发不出、眼镜面板不亮（用户："眼镜里没有画面"）。
    #
    # 判据用 HID 报告描述符，不依赖编号也不写死 VID/PID：
    #   控制接口 (12d1:107e input3) 以 `06 ff 00` 开头（Vendor Defined
    #     Usage Page），report 1 是 59 字节 IMU、Feature id4 127 字节；
    #   Consumer Control 按键口以 `05 0c` 开头，Hub 以 `05 01` 开头。
    # 注：ksu/hwvr_compat/hwvr-probe 的 GET_FEATURE(4) 在本机 hidraw0/1
    #     都返回 0，无法区分，所以这里用描述符。
    CTRL=""
    for h in /sys/class/hidraw/hidraw*; do
        [ -e "$h" ] || continue
        d=$(od -An -tx1 -N3 -v "$h/device/report_descriptor" 2>/dev/null | tr -d ' \n')
        [ "$d" = "06ff00" ] && { CTRL="/dev/${h##*/}"; break; }
    done
    if [ -n "$CTRL" ]; then
        CUR=$(readlink /dev/hwvr1 2>/dev/null)
        if [ "$CUR" != "$CTRL" ]; then
            rm -f /dev/hwvr1
            ln -s "$CTRL" /dev/hwvr1 2>/dev/null && \
                echo "$(date) hwvr1 -> $CTRL (control iface)" >> "$LOG"
        fi
    else
        # 眼镜拔出：摘掉死链，等插回再建
        if [ -L /dev/hwvr1 ]; then
            rm -f /dev/hwvr1
            echo "$(date) hwvr1 清除（控制接口不在位）" >> "$LOG"
        fi
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
