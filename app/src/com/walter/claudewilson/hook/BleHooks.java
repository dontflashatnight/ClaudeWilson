package com.walter.claudewilson.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.walter.claudewilson.model.BleProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Target-process BLE view, adapted from VirtualRegion {@code G3.W}/{@code L3.C0088g}:
 * the scanner registration is blocked and a virtual {@link ScanResult} is delivered to the
 * target's callback on a timer; bonded devices are built from the same list.
 */
final class BleHooks {
    static volatile String status = "not-run";
    static final java.util.concurrent.atomic.AtomicInteger scanServed =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger bondedServed =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final Map<Object, ScanTask> REGISTRATIONS =
            Collections.synchronizedMap(new IdentityHashMap<Object, ScanTask>());
    private static final Map<String, String> NAMES =
            Collections.synchronizedMap(new java.util.HashMap<String, String>());
    private static volatile ClassLoader classLoader;

    private BleHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        classLoader = loader;
        installLocalNameCloak(loader, config);
        if (config.ble.isEmpty() && !config.bleEmpty) {
            status = "off";
            return;
        }
        for (BleProfile.Device device : config.ble) {
            NAMES.put(device.address, device.name);
        }
        try {
            Class<?> adapter = XposedHelpers.findClass("android.bluetooth.BluetoothAdapter", loader);
            XposedBridge.hookAllMethods(adapter, "getBondedDevices", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!active() || !(param.thisObject instanceof BluetoothAdapter)) {
                        return;
                    }
                    BluetoothAdapter bluetooth = (BluetoothAdapter) param.thisObject;
                    Set<BluetoothDevice> devices = new HashSet<>();
                    for (BleProfile.Device device : config.ble) {
                        try {
                            devices.add(bluetooth.getRemoteDevice(device.address));
                        } catch (Throwable ignored) {
                        }
                    }
                    param.setResult(devices);
                    bondedServed.incrementAndGet();
                }
            });
            Class<?> btManager = XposedHelpers.findClassIfExists(
                    "android.bluetooth.BluetoothManager", loader);
            if (btManager != null) {
                XC_MethodHook connectedHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!active()) return;
                        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
                        if (!config.bleEmpty && !config.ble.isEmpty()) {
                            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                            if (bt != null) {
                                for (BleProfile.Device d : config.ble) {
                                    if (!d.bonded) continue;
                                    try {
                                        out.add(bt.getRemoteDevice(d.address));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }
                        param.setResult(out);
                    }
                };
                XposedBridge.hookAllMethods(btManager, "getConnectedDevices", connectedHook);
                XposedBridge.hookAllMethods(btManager, "getDevicesMatchingConnectionStates",
                        connectedHook);
            }
            Class<?> scanner = XposedHelpers.findClass(
                    "android.bluetooth.le.BluetoothLeScanner", loader);
            XposedBridge.hookAllMethods(scanner, "startScan", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!active()) {
                        return;
                    }
                    ScanCallback callback = null;
                    for (Object arg : param.args) {
                        if (arg instanceof ScanCallback) {
                            callback = (ScanCallback) arg;
                            break;
                        }
                    }
                    if (callback == null) {
                        return;
                    }
                    param.setResult(null);
                    stop(callback);
                    ScanTask task = new ScanTask(callback, config.ble);
                    REGISTRATIONS.put(callback, task);
                    task.start();
                }
            });
            XposedBridge.hookAllMethods(scanner, "stopScan", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        stop(arg);
                    }
                }
            });
            Class<?> device = XposedHelpers.findClass("android.bluetooth.BluetoothDevice", loader);
            XC_MethodHook deviceNameHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!active() || !(param.thisObject instanceof BluetoothDevice)) {
                        return;
                    }
                    try {
                        String address = ((BluetoothDevice) param.thisObject).getAddress();
                        String name = NAMES.get(address);
                        if (name != null) {
                            param.setResult(name);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(device, "getName", deviceNameHook);
            XposedBridge.hookAllMethods(device, "getAlias", deviceNameHook);
            status = "ok";
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook ble status=ok devices=" + config.ble.size());
            }
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook ble: " + t);
        }
    }

    /** Masks local Bluetooth/device owner name with the active persona model name. */
    private static void installLocalNameCloak(ClassLoader loader, HookConfig config) {
        String model = config.build.get("MODEL");
        if (model == null || model.trim().isEmpty()) {
            return;
        }
        final String spoofName = model.trim();
        try {
            Class<?> adapter = XposedHelpers.findClassIfExists(
                    "android.bluetooth.BluetoothAdapter", loader);
            if (adapter != null) {
                XposedBridge.hookAllMethods(adapter, "getName",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(spoofName);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> secure = XposedHelpers.findClassIfExists(
                    "android.provider.Settings$Secure", loader);
            if (secure != null) {
                XC_MethodHook btNameHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 2
                                && "bluetooth_name".equals(String.valueOf(param.args[1]))) {
                            param.setResult(spoofName);
                        }
                    }
                };
                XposedBridge.hookAllMethods(secure, "getString", btNameHook);
                XposedBridge.hookAllMethods(secure, "getStringForUser", btNameHook);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> global = XposedHelpers.findClassIfExists(
                    "android.provider.Settings$Global", loader);
            if (global != null) {
                XC_MethodHook devNameHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 2
                                && "device_name".equals(String.valueOf(param.args[1]))) {
                            param.setResult(spoofName);
                        }
                    }
                };
                XposedBridge.hookAllMethods(global, "getString", devNameHook);
                XposedBridge.hookAllMethods(global, "getStringForUser", devNameHook);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean active() {
        Context context = HookContext.app(classLoader);
        return context != null && permitted(context);
    }

    private static boolean permitted(Context context) {
        if (HookContext.HOST_SDK_INT >= 31) {
            return context.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == 0
                    || context.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0;
        }
        return context.checkSelfPermission("android.permission.BLUETOOTH") == 0;
    }

    private static void stop(Object callback) {
        ScanTask task = REGISTRATIONS.remove(callback);
        if (task != null) {
            task.stop();
        }
    }

    private static final class ScanTask implements Runnable {
        private final ScanCallback callback;
        private final List<BleProfile.Device> devices;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private volatile boolean active = true;

        ScanTask(ScanCallback callback, List<BleProfile.Device> devices) {
            this.callback = callback;
            this.devices = new ArrayList<>(devices);
        }

        void start() {
            handler.post(this);
        }

        void stop() {
            active = false;
            handler.removeCallbacks(this);
        }

        @Override public void run() {
            if (!active) {
                return;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                for (BleProfile.Device device : devices) {
                    try {
                        BluetoothDevice remote = adapter.getRemoteDevice(device.address);
                        ScanResult result = new ScanResult(remote,
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                                ScanSettings.SCAN_MODE_LOW_LATENCY,
                                0, 0, device.txPower, device.rssi, 0, null,
                                SystemClock.elapsedRealtimeNanos());
                        XposedHelpers.callMethod(callback, "onScanResult",
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                        scanServed.incrementAndGet();
                    } catch (Throwable t) {
                        XposedBridge.log("ClaudeWilsonHook ble result: " + t);
                    }
                }
            }
            if (active) {
                handler.postDelayed(this, 1000L);
            }
        }
    }
}
