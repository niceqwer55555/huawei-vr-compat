.class public Lcom/huawei/vrhandle/AutoScanCompat;
.super Ljava/lang/Object;
.source "AutoScanCompat.java"
.implements Ljava/lang/Runnable;


# static fields
.field private static sLastKickMs:J

# instance fields
.field private mService:Lcom/huawei/vrhandle/service/VRHandleService;


# direct methods
.method public constructor <init>(Lcom/huawei/vrhandle/service/VRHandleService;)V
    .registers 2

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/huawei/vrhandle/AutoScanCompat;->mService:Lcom/huawei/vrhandle/service/VRHandleService;

    return-void
.end method


# 由 VRHandleService.onCreate / onStartCommand 调用。
#
# 关键时序（2026-11-07 实测钉死）：
#   直接在这里 startScan 会早于 Service 的 startForeground()，蓝牙栈把
#   调用者判成后台，日志 E BluetoothUtils: Permission denial: Need
#   ACCESS_FINE_LOCATION permission to get scan results，
#   dumpsys bluetooth_manager 里 AppScanStats 显示 42 次扫描、
#   Total number of results: 0 —— 扫了但一个结果都拿不到。
#   logcat 顺序：onScannerRegistered -> Permission denial ->
#                show common foreground notification（前台通知在后）。
#   所以这里只做「安排」：用主 Looper 的 Handler 延迟 3 秒，等
#   startForeground() 完成后再由 run() 真正启动扫描。
#
# 60 秒节流：onCreate 冷启动安排一次；之后 VrSystemActor 每 20 秒发
# ACTION_RECONNECT_MSG，onStartCommand 再次调用，但 60 秒内不重复安排。
# 手柄晚开机 / 休眠唤醒后会在下一轮自动被扫到。
#
# 全程 try/catch：任何环境异常都静默跳过，绝不影响 Service 原有流程。
.method public static kick(Lcom/huawei/vrhandle/service/VRHandleService;)V
    .registers 6

    :try_start_0
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v0

    sget-wide v2, Lcom/huawei/vrhandle/AutoScanCompat;->sLastKickMs:J

    sub-long/2addr v0, v2

    const-wide/32 v2, 0xea60

    cmp-long v4, v0, v2

    if-gez v4, :do_scan

    return-void

    :do_scan
    invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J

    move-result-wide v0

    sput-wide v0, Lcom/huawei/vrhandle/AutoScanCompat;->sLastKickMs:J

    const-string v0, "AutoScanCompat"

    const-string v1, "kick: schedule BLE scan after foreground +3s"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    new-instance v0, Landroid/os/Handler;

    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;

    move-result-object v1

    invoke-direct {v0, v1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    new-instance v1, Lcom/huawei/vrhandle/AutoScanCompat;

    invoke-direct {v1, p0}, Lcom/huawei/vrhandle/AutoScanCompat;-><init>(Lcom/huawei/vrhandle/service/VRHandleService;)V

    const-wide/16 v2, 0xbb8

    invoke-virtual {v0, v1, v2, v3}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    return-void
.end method


# 延迟到 Service 前台通知之后才真正启动 BLE 扫描。
# t0/y0 是 vrhandle 自带的扫描管理器（R() 自初始化单例，构造函数从
# VrHandleApplication 取 Context 与 BluetoothAdapter）；N(Lp0/c) 即
# startScan；回调用 Service 已构造好的 m 字段（VRHandleService$d
# implements BluetoothDeviceDiscoverCallback），扫到设备后原链路走
# N2(BluetoothDevice) 入库并连接，不需要配对页 UI。
.method public run()V
    .registers 3

    :try_start_0
    const-string v0, "AutoScanCompat"

    const-string v1, "run: auto BLE scan (foreground ready)"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    invoke-static {}, Lt0/y0;->R()Lt0/y0;

    move-result-object v0

    iget-object v1, p0, Lcom/huawei/vrhandle/AutoScanCompat;->mService:Lcom/huawei/vrhandle/service/VRHandleService;

    iget-object v1, v1, Lcom/huawei/vrhandle/service/VRHandleService;->m:Lp0/c;

    invoke-virtual {v0, v1}, Lt0/y0;->N(Lp0/c;)V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    return-void
.end method
