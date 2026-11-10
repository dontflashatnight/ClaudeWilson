package com.walter.claudewilson.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
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

    private static final Map<Object, ScanTask> REGISTRATIONS =
            Collections.synchronizedMap(new IdentityHashMap<Object, ScanTask>());
    private static final Map<String, String> NAMES =
            Collections.synchronizedMap(new java.util.HashMap<String, String>());
    private static volatile ClassLoader classLoader;

    private BleHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        if (config.ble.isEmpty()) {
            status = "off";
            return;
        }
        classLoader = loader;
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
                }
            });
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
            XposedBridge.hookAllMethods(device, "getName", new XC_MethodHook() {
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
            });
            status = "ok";
            XposedBridge.log("ClaudeWilsonHook ble status=ok devices=" + config.ble.size());
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook ble: " + t);
        }
    }

    private static boolean active() {
        Context context = HookContext.app(classLoader);
        return context != null && permitted(context);
    }

    private static boolean permitted(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
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
                                0, 0, 0, device.rssi, 0, null,
                                SystemClock.elapsedRealtimeNanos());
                        XposedHelpers.callMethod(callback, "onScanResult",
                                ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
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
