package com.hvr.shell;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 手柄（CF20）的自动扫描与 <b>自有 GATT 直连</b>。
 *
 * <p><b>演进：</b>r10~r14 走「扫描 → 通知 vrhandle 直连」，被 vrhandle 的设备库
 * 卡死——它的重连只认自己库里 activeState=1 的设备，而激活记录的写盘依赖华为
 * iconnect/vrservice 全家桶（配对界面显示成功但没落盘，进程重启后库仍为空）。
 * 复活那条链 = 重新依赖华为栈，用户已裁决放弃。</p>
 *
 * <p><b>r15 起自力更生：CF20 是标准 HID-over-GATT 设备</b>（免配对，vrhandle
 * smali 佐证只有一条 connectGatt 直连路径），本应用自己 connectGatt、订阅
 * HID report 通知（0x1812），收到的原始报告先全量打日志（键位映射下一版
 * 按日志定），后续直接驱动 {@link DesktopPresenter}。</p>
 *
 * <p>扫描前置（缺一则回调静默无结果，r10 实测）：ACCESS_FINE_LOCATION 已授权
 * + 系统定位开关打开 + 蓝牙开。</p>
 */
final class ControllerLink {

    /** HID-over-GATT 服务。 */
    private static final UUID HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID HID_PROTOCOL_MODE =
            UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb");
    private static final UUID HID_REPORT =
            UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb");
    /** HID Report Map（描述符）：报告里每个字节/位用在哪。 */
    private static final UUID HID_REPORT_DESCRIPTOR =
            UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb");
    /** vrhandle 里的私有 BLE 服务（CF20 数据通道可能也挂在这里，一并订阅）。 */
    private static final UUID CONTROLLER_SERVICE =
            UUID.fromString("233827e4-09cc-45a4-a59b-4d024138cbbe");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_WINDOW_MS = 12000L;
    private static final long SCAN_RETRY_MS = 20000L;
    private static final long RECONNECT_MS = 4000L;
    /** r19：默认不写 0x2A4E（会卡死 EMUI GATT 队列，见 begin 里的注释）。 */
    private static final boolean WRITE_PROTOCOL_MODE = false;

    private static final String PREFS = "controller";
    private static final String KEY_MAC = "controller_mac";

    private static ControllerLink instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean running;

    private BluetoothGatt gatt;
    /** 待订阅的 CCCD 队列（GATT 一次只能挂起一个写操作）。 */
    private final java.util.ArrayDeque<BluetoothGattDescriptor> cccdQueue =
            new java.util.ArrayDeque<>();
    private int reportCount;
    private long lastReportLogAt;
    /** r17 诊断：订阅串行化状态机。 */
    private boolean protocolPending;
    private boolean cccdStarted;
    private int cccdTotal;
    private int cccdDone;
    private int cccdFailed;
    private BluetoothGattDescriptor currentCccd;
    private int cccdRetry;
    private Runnable pendingTimeout;

    private ControllerLink(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void start(Context context) {
        if (context == null) {
            return;
        }
        synchronized (ControllerLink.class) {
            if (instance == null) {
                instance = new ControllerLink(context.getApplicationContext());
            }
        }
        instance.begin();
    }

    static void stop() {
        synchronized (ControllerLink.class) {
            if (instance == null) {
                return;
            }
        }
        instance.end();
    }

    // ------------------------------------------------------------------ 生命周期

    private void begin() {
        if (running) {
            return;
        }
        running = true;
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            ShellState.controllerNote = "本机没有蓝牙适配器";
            running = false;
            return;
        }
        if (!ensureEnabled()) {
            running = false;
            return;
        }
        String saved = prefs.getString(KEY_MAC, null);
        if (saved != null) {
            // 上次连过的地址：先盲连一次（手柄若开机在旁，无需等广播）。
            ShellState.controllerNote = "手柄重连：" + saved;
            connectSaved(saved);
        }
        startScan();
        handler.postDelayed(scanTimeout, SCAN_WINDOW_MS);
    }

    private void end() {
        if (!running) {
            return;
        }
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        ShellState.controllerNote = "眼镜已拔出，停止";
    }

    private boolean ensureEnabled() {
        try {
            if (adapter.isEnabled()) {
                return true;
            }
            boolean enabled = adapter.enable();
            ShellState.event("请求打开蓝牙 -> " + enabled);
            if (!enabled) {
                ShellState.controllerNote = "蓝牙未开，且系统拒绝了自动打开";
            }
            return enabled;
        } catch (Throwable error) {
            ShellState.event("打开蓝牙被拒", error);
            ShellState.controllerNote = "蓝牙未开：" + error.getClass().getSimpleName();
            return false;
        }
    }

    // ------------------------------------------------------------------ 扫描

    private void startScan() {
        if (scanning || !running || adapter == null) {
            return;
        }
        if (gatt != null) {
            return; // 已连上就不再扫
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            ShellState.controllerNote = "蓝牙扫描不可用（适配器未就绪）";
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.<ScanFilter>emptyList(), settings, callback);
            scanning = true;
            ShellState.controllerNote = "扫描中";
        } catch (Throwable error) {
            ShellState.event("启动扫描失败", error);
            ShellState.controllerNote = "扫描失败：" + error.getClass().getSimpleName();
        }
    }

    private void stopScan() {
        if (!scanning || scanner == null) {
            return;
        }
        try {
            scanner.stopScan(callback);
        } catch (Throwable error) {
            // 静默
        }
        scanning = false;
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            stopScan();
            if (gatt == null) {
                ShellState.controllerNote = "本轮没扫到，稍后重试";
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running && gatt == null) {
                        startScan();
                        handler.postDelayed(scanTimeout, SCAN_WINDOW_MS);
                    }
                }
            }, SCAN_RETRY_MS);
        }
    };

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onFound(result == null ? null : result.getDevice());
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (ScanResult result : results) {
                onFound(result == null ? null : result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            ShellState.event("扫描失败 code=" + errorCode);
            ShellState.controllerNote = "扫描失败 code=" + errorCode;
            scanning = false;
        }
    };

    private void onFound(BluetoothDevice device) {
        if (device == null || !running || gatt != null) {
            return;
        }
        String name;
        try {
            name = device.getName();
        } catch (Throwable error) {
            name = null;
        }
        if (name == null || !looksLikeController(name)) {
            return;
        }
        String address = device.getAddress();
        prefs.edit().putString(KEY_MAC, address).apply();
        stopScan();
        ShellState.controllerNote = "已找到 " + name + "，GATT 直连中";
        ShellState.event("扫到手柄 " + address + " " + name + "，自有 GATT 直连");
        connect(device);
    }

    private void connectSaved(String address) {
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            connect(device);
        } catch (Throwable error) {
            ShellState.event("盲连失败（等扫描）", error);
        }
    }

    private static boolean looksLikeController(String name) {
        String upper = name.toUpperCase();
        return upper.contains("CF20")
                || upper.contains("VR GLASS")
                || upper.contains("VRGLASS")
                || upper.contains("HUAWEI VR");
    }

    // ------------------------------------------------------------------ GATT

    private void connect(BluetoothDevice device) {
        if (gatt != null) {
            return;
        }
        try {
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable error) {
            ShellState.event("connectGatt 失败", error);
            gatt = null;
        }
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Throwable ignored) {
                // 静默
            }
            gatt = null;
        }
        cccdQueue.clear();
        cccdStarted = false;
        protocolPending = false;
        currentCccd = null;
        cccdRetry = 0;
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        ShellState.controllerNote = "手柄未连接";
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ShellState.controllerNote = "手柄已连接，发现服务中";
                ShellState.event("GATT 已连接，开始发现服务");
                try {
                    g.discoverServices();
                } catch (Throwable error) {
                    ShellState.event("discoverServices 失败", error);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ShellState.event("GATT 断开 status=" + status + "，已收 " + reportCount + " 包");
                closeGatt();
                if (running) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (running && gatt == null) {
                                String saved = prefs.getString(KEY_MAC, null);
                                if (saved != null) {
                                    ShellState.controllerNote = "手柄重连中";
                                    connectSaved(saved);
                                } else {
                                    startScan();
                                    handler.postDelayed(scanTimeout, SCAN_WINDOW_MS);
                                }
                            }
                        }
                    }, RECONNECT_MS);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ShellState.event("服务发现失败 status=" + status);
                return;
            }
            subscribeHid(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (pendingTimeout != null) {
                handler.removeCallbacks(pendingTimeout);
            }
            String target = descriptor == null ? "?" : String.valueOf(descriptor.getCharacteristic().getUuid());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cccdDone++;
                currentCccd = null;
                cccdRetry = 0;
                ShellState.event("CCCD 订阅成功 " + cccdDone + "/" + cccdTotal + " -> " + target);
            } else {
                retryCccd(descriptor == null ? null
                        : descriptor.getCharacteristic().getUuid(), "status=" + status);
                return;
            }
            writeNextCccd();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g,
                                        BluetoothGattCharacteristic characteristic,
                                        int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ShellState.event("读特征失败 status=" + status
                        + " " + characteristic.getUuid());
                return;
            }
            ShellState.event("特征值 " + shortUuid(characteristic.getUuid())
                    + " len=" + (characteristic.getValue() == null
                    ? 0 : characteristic.getValue().length)
                    + " hex=" + hex(characteristic.getValue()));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (pendingTimeout != null) {
                handler.removeCallbacks(pendingTimeout);
            }
            if (protocolPending) {
                protocolPending = false;
                ShellState.event("协议模式写入回调 status=" + status + "，开始订阅");
                startCccdQueue();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            onReport(characteristic);
        }
    };

    /** 订阅 HID 服务（与私有服务）下全部可通知特征；协议模式先切 report。 */
    private void subscribeHid(BluetoothGatt g) {
        cccdQueue.clear();
        protocolPending = false;
        cccdStarted = false;
        cccdTotal = 0;
        cccdDone = 0;
        cccdFailed = 0;
        dumpServices(g);

        boolean foundHid = false;
        BluetoothGattCharacteristic protocolChar = null;
        for (BluetoothGattService service : g.getServices()) {
            UUID uuid = service.getUuid();
            boolean interesting = HID_SERVICE.equals(uuid) || CONTROLLER_SERVICE.equals(uuid);
            if (!interesting) {
                continue;
            }
            if (HID_SERVICE.equals(uuid)) {
                foundHid = true;
                BluetoothGattCharacteristic protocol = service.getCharacteristic(HID_PROTOCOL_MODE);
                int props = protocol == null ? 0 : protocol.getProperties();
                if (protocol != null
                        && (props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                    protocolChar = protocol;
                }
            }
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if ((characteristic.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    continue;
                }
                BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
                if (cccd != null) {
                    cccdQueue.add(cccd);
                }
            }
        }
        cccdTotal = cccdQueue.size();
        ShellState.event("HID 服务" + (foundHid ? "已找到" : "未找到（只有私有服务）")
                + "，待订阅 " + cccdTotal + " 路通知");
        reportCount = 0;

        if (protocolChar != null && WRITE_PROTOCOL_MODE) {
            // r17：协议模式写与 CCCD 写必须串行（GATT 同时只允许一个挂起操作，并发会静默丢弃）。
            protocolPending = true;
            boolean noResponse = (protocolChar.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
            protocolChar.setWriteType(noResponse
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ShellState.event("写 0x2A4E 协议模式 = Report(01)"
                    + (noResponse ? "（无响应写）" : "（有响应写）"));
            try {
                protocolChar.setValue(new byte[]{0x01});
                if (!g.writeCharacteristic(protocolChar)) {
                    protocolPending = false;
                    ShellState.event("协议模式写发起失败，直接订阅");
                }
            } catch (Throwable error) {
                protocolPending = false;
                ShellState.event("协议模式写入异常", error);
            }
            watchdog(3000L, new Runnable() {
                @Override
                public void run() {
                    if (protocolPending) {
                        protocolPending = false;
                        ShellState.event("协议模式写无回调（超时），继续订阅");
                        startCccdQueue();
                    }
                }
            });
            if (!protocolPending) {
                startCccdQueue();
            }
        } else {
            // r19：HID 默认就是 Report 协议，这条写可以省。
            // 且 EMUI 的 bt_stack 对「无响应写」的完成事件不处理
            // （日志：btif_gattc_upstreams_evt: Unhandled event (8)），
            // 写下去会把整条 GATT 队列卡死，后续 CCCD 全被拒——r18 实测。
            ShellState.event("跳过 0x2A4E 协议模式写，直接订阅通知");
            startCccdQueue();
        }
    }

    /** 打印整棵 GATT 树（r17 诊断：确认手柄到底暴露了哪些通道）。 */
    private void dumpServices(BluetoothGatt g) {
        StringBuilder sb = new StringBuilder("GATT 树：");
        for (BluetoothGattService service : g.getServices()) {
            sb.append("\n  S ").append(service.getUuid());
            for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                sb.append("\n    C ").append(ch.getUuid())
                        .append(" props=0x").append(Integer.toHexString(ch.getProperties()));
            }
        }
        ShellState.event(sb.toString());
    }

    private void startCccdQueue() {
        if (cccdStarted) {
            return;
        }
        cccdStarted = true;
        writeNextCccd();
    }

    private void watchdog(long delayMs, final Runnable action) {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
        }
        pendingTimeout = new Runnable() {
            @Override
            public void run() {
                action.run();
            }
        };
        handler.postDelayed(pendingTimeout, delayMs);
    }

    private void writeNextCccd() {
        if (currentCccd == null) {
            currentCccd = cccdQueue.poll();
            cccdRetry = 0;
        }
        if (currentCccd == null) {
            if (gatt != null) {
                ShellState.controllerNote = "手柄已连接";
                ShellState.event("订阅完成 " + cccdDone + "/" + cccdTotal
                        + " 路（失败 " + cccdFailed + "），等待手柄数据");
                readReportDescriptor(gatt);
            }
            return;
        }
        attemptCccd();
    }

    private void attemptCccd() {
        final BluetoothGattDescriptor cccd = currentCccd;
        if (cccd == null || gatt == null) {
            return;
        }
        final UUID target = cccd.getCharacteristic().getUuid();
        try {
            gatt.setCharacteristicNotification(cccd.getCharacteristic(), true);
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(cccd)) {
                retryCccd(target, "写发起被拒");
                return;
            }
            watchdog(6000L, new Runnable() {
                @Override
                public void run() {
                    retryCccd(target, "无回调超时");
                }
            });
        } catch (Throwable error) {
            retryCccd(target, "异常 " + error.getClass().getSimpleName());
        }
    }

    private void retryCccd(UUID target, String why) {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        if (cccdRetry < 3) {
            cccdRetry++;
            ShellState.event("CCCD " + why + "，" + target + " 第 " + cccdRetry + " 次重试");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptCccd();
                }
            }, 900L);
        } else {
            cccdFailed++;
            ShellState.event("CCCD 放弃 " + target + "（" + why + "）");
            currentCccd = null;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    writeNextCccd();
                }
            }, 300L);
        }
    }

    /** r18：读 HID Report Descriptor（0x2A4B）——6DoF 报告的字段布局全在这里。 */
    private void readReportDescriptor(BluetoothGatt g) {
        for (BluetoothGattService service : g.getServices()) {
            if (!HID_SERVICE.equals(service.getUuid())) {
                continue;
            }
            BluetoothGattCharacteristic descriptor =
                    service.getCharacteristic(HID_REPORT_DESCRIPTOR);
            if (descriptor == null) {
                ShellState.event("没有 0x2A4B 报告描述符");
                return;
            }
            try {
                if (g.readCharacteristic(descriptor)) {
                    ShellState.event("读 HID Report Descriptor…");
                }
            } catch (Throwable error) {
                ShellState.event("读报告描述符失败", error);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 收到手柄报告：先全量记日志（键位映射素材），限频防刷屏。 */
    private void onReport(BluetoothGattCharacteristic characteristic) {
        reportCount++;
        byte[] data = characteristic.getValue();
        ShellState.controllerNote = "手柄已连接（数据 " + reportCount + " 包）";
        long now = android.os.SystemClock.elapsedRealtime();
        boolean head = reportCount <= 12; // r17：前 12 包无条件记，便于看清包结构
        if (head || (now - lastReportLogAt >= 400L && reportCount <= 400)) {
            lastReportLogAt = now;
            ShellState.event("手柄报告[" + reportCount + "] uuid="
                    + shortUuid(characteristic.getUuid())
                    + " len=" + (data == null ? 0 : data.length)
                    + " hex=" + hex(data));
        }
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "(空)";
        }
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", b));
        }
        return builder.toString();
    }
}
