package com.hvr.shell;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 手柄（CF20）的自动扫描与配对。
 *
 * 为什么不是「自己连 GATT」：手柄是华为私有 BLE 协议，连上之后的服务、特征、
 * 报文全在 {@code com.huawei.vrhandle} 里。总控再实现一遍等于重写协议，而且会和
 * 原版抢同一个 GATT 连接。所以这里只做原版做不到的两件事：
 *
 * <ol>
 *   <li><b>把蓝牙打开、把设备扫出来</b> —— 原版扫描需要蓝牙已开且设备在范围内；</li>
 *   <li><b>把配对（bond）真正完成</b> —— 台账里 CF20 长期停在
 *       {@code bond_type:UNKNOWN}、没有 link key，链路会被远端周期性断开，
 *       这正是「手柄老是掉」的根因。配对是系统层的事，原版应用无权做。</li>
 * </ol>
 *
 * 配对完成后只发一条原版约定的广播动作 {@code com.huawei.vrservice.ACTION_RECONNECT_MSG}
 * 给 vrhandle 的服务，让它走它自己的 {@code connectGatt}。连接本身仍然由原版负责。
 *
 * 扫描过滤条件不是猜的：{@code 233827e4-09cc-45a4-a59b-4d024138cbbE} 是从
 * vrhandle 的 smali 里读出来的服务 UUID（配套特征 233827e5-…，CCCD 00002902）。
 */
final class ControllerLink {

    /** vrhandle 里的私有 BLE 服务 UUID，用于精确过滤广播包。 */
    private static final UUID CONTROLLER_SERVICE =
            UUID.fromString("233827e4-09cc-45a4-a59b-4d024138cbbe");

    /** 原版 vrservice 发给手柄的重连动作（vrhandle 的 intent-filter 里就有它）。 */
    private static final String ACTION_RECONNECT = "com.huawei.vrservice.ACTION_RECONNECT_MSG";
    private static final String HANDLE_PACKAGE = "com.huawei.vrhandle";
    private static final String HANDLE_SERVICE = "com.huawei.vrhandle.service.VRHandleService";

    private static final long SCAN_WINDOW_MS = 12000L;
    private static final long SCAN_RETRY_MS = 20000L;

    private static final String PREFS = "controller";
    private static final String KEY_MAC = "bonded_mac";

    private static ControllerLink instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean receiverRegistered;
    private boolean running;

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
        registerPairingReceiver();
        if (!ensureEnabled()) {
            running = false;
            return;
        }
        ShellState.controllerNote = "已配对：查找中";
        // 已经配对过的直接用它，不必再扫（已配对设备不再广播）。
        if (alreadyBonded()) {
            return;
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
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(pairingReceiver);
            } catch (Throwable error) {
                ShellState.event("注销配对监听失败", error);
            }
            receiverRegistered = false;
        }
        ShellState.controllerNote = "眼镜已拔出，停止";
    }

    private boolean ensureEnabled() {
        try {
            if (adapter.isEnabled()) {
                return true;
            }
            // targetSdk 30 仍允许应用请求打开蓝牙；被 OEM 拦掉时如实记录，
            // 不弹任何界面（用户要的是「插上就能用」，不是先去点一下）。
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

    // ------------------------------------------------------------------ 已配对设备

    private boolean alreadyBonded() {
        String saved = prefs.getString(KEY_MAC, null);
        if (saved != null) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(saved);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    ShellState.controllerNote = "已配对：" + saved;
                    nudgeHandle();
                    return true;
                }
            } catch (Throwable error) {
                ShellState.event("读取已配对设备失败", error);
            }
        }
        // 换机/清数据后没有记录：把已配对列表里名字像手柄的捞出来试一试。
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name != null && looksLikeController(name)) {
                    prefs.edit().putString(KEY_MAC, device.getAddress()).apply();
                    ShellState.controllerNote = "已配对：" + name;
                    nudgeHandle();
                    return true;
                }
            }
        } catch (Throwable error) {
            ShellState.event("遍历已配对设备失败", error);
        }
        return false;
    }

    private static boolean looksLikeController(String name) {
        String upper = name.toUpperCase();
        return upper.contains("CF20")
                || upper.contains("VR GLASS")
                || upper.contains("VRGLASS")
                || upper.contains("HUAWEI VR");
    }

    // ------------------------------------------------------------------ 扫描

    private void startScan() {
        if (scanning || !running || adapter == null) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            ShellState.controllerNote = "蓝牙扫描不可用（适配器未就绪）";
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(CONTROLLER_SERVICE))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanning = true;
            ShellState.controllerNote = "扫描中";
            ShellState.event("开始按服务 UUID 扫描手柄");
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
            ShellState.event("停止扫描失败", error);
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
            ShellState.controllerNote = "本轮没扫到，稍后重试";
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running && !alreadyBonded()) {
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
        if (device == null || !running) {
            return;
        }
        String address = device.getAddress();
        String name;
        try {
            name = device.getName();
        } catch (Throwable error) {
            name = null;
        }
        ShellState.event("扫到手柄候选 " + address + " name=" + name);

        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = device.getBondState();
        } catch (Throwable error) {
            ShellState.event("读取配对状态失败", error);
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            prefs.edit().putString(KEY_MAC, address).apply();
            ShellState.controllerNote = "已配对：" + address;
            stopScan();
            nudgeHandle();
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDING) {
            ShellState.controllerNote = "配对中：" + address;
            return;
        }
        try {
            boolean started = device.createBond();
            ShellState.controllerNote = "发起配对：" + address + " -> " + started;
            ShellState.event("createBond " + started);
        } catch (Throwable error) {
            ShellState.event("发起配对失败", error);
            ShellState.controllerNote = "配对失败：" + error.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ 配对与重连

    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            BluetoothDevice device = intent == null
                    ? null
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String action = intent == null ? null : intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR);
                onBondState(device, state);
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                answerPairing(device);
            }
        }
    };

    private void registerPairingReceiver() {
        if (receiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            // SYSTEM_HIGH_PRIORITY = 1000，是 @hide 常量，这里写字面值。
            filter.setPriority(1000);
            context.registerReceiver(pairingReceiver, filter);
            receiverRegistered = true;
        } catch (Throwable error) {
            ShellState.event("注册配对监听失败", error);
        }
    }

    private void onBondState(BluetoothDevice device, int state) {
        String address = device == null ? "?" : device.getAddress();
        if (state == BluetoothDevice.BOND_BONDED) {
            if (device != null) {
                prefs.edit().putString(KEY_MAC, address).apply();
            }
            ShellState.controllerNote = "配对成功：" + address;
            ShellState.event("配对成功 " + address + "，通知 vrhandle 重连");
            stopScan();
            nudgeHandle();
        } else if (state == BluetoothDevice.BOND_BONDING) {
            ShellState.controllerNote = "配对中：" + address;
        } else if (state == BluetoothDevice.BOND_NONE) {
            ShellState.controllerNote = "配对未完成：" + address;
            ShellState.event("配对未完成 " + address + "，稍后重扫");
        }
    }

    /**
     * 自动应答 PIN。
     *
     * CF20 是 Just Works 之外还可能弹 PIN 的那一类；戴着眼镜时用户没法去点系统
     * 弹窗，所以这里用系统提供的 {@code setPin}/{@code setPairingConfirmation}
     * 直接把 PIN 交上去。这两个是隐藏 API，Android 9+ 对非 SDK 接口有名单限制，
     * 被拦时只记录不重试 —— 装机脚本会先设 hidden_api_policy 放开。
     */
    private void answerPairing(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        String[] candidates = {"0000", "1234"};
        for (String pin : candidates) {
            if (invoke(device, "setPin", new Class<?>[]{byte[].class},
                    new Object[]{pin.getBytes()})) {
                ShellState.event("已自动提交 PIN " + pin);
                break;
            }
        }
        invoke(device, "setPairingConfirmation", new Class<?>[]{boolean.class},
                new Object[]{Boolean.TRUE});
    }

    /**
     * setPin / setPairingConfirmation 是 @hide 接口，编译期的 android.jar 里没有，
     * 只能反射调用。被非 SDK 接口名单拦住时返回 false，只记录不重试。
     */
    private boolean invoke(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            java.lang.reflect.Method call = target.getClass().getMethod(method, types);
            call.setAccessible(true);
            Object result = call.invoke(target, args);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable error) {
            ShellState.event("隐藏 API " + method + " 不可用", error);
            return false;
        }
    }

    /**
     * 通知 vrhandle：手柄已配对，去连。
     *
     * 这条动作是原版 vrservice 在「VR 已启动」时发给手柄的那一条，不是我们编的
     * 新协议；vrhandle 的 intent-filter 与 dispatch 表里都有它，走的是它自己的
     * 「绑定桌面重连服务失败 → 直接 connectGatt」兜底路径。
     */
    private void nudgeHandle() {
        Intent intent = new Intent(ACTION_RECONNECT);
        intent.setClassName(HANDLE_PACKAGE, HANDLE_SERVICE);
        try {
            context.startService(intent);
            ShellState.event("已通知 vrhandle 重连手柄");
        } catch (Throwable error) {
            ShellState.event("通知 vrhandle 失败", error);
        }
    }
}
