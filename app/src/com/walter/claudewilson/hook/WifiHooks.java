package com.walter.claudewilson.hook;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import com.walter.claudewilson.model.WifiProfile;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Target-process Wi-Fi view, ported from VirtualRegion {@code G3.Y}/{@code G3.D0}:
 * the scan list and connection info are rebuilt from the region's virtual AP list instead of
 * being read from the real scan cache.
 */
final class WifiHooks {
    static volatile String status = "not-run";
    static final java.util.concurrent.atomic.AtomicInteger scanServed =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger connectionServed =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile ClassLoader classLoader;

    private WifiHooks() {
    }

    static void install(ClassLoader loader, HookConfig config) {
        if (config.wifi.isEmpty()) {
            status = "off";
            return;
        }
        classLoader = loader;
        final List<WifiProfile.Ap> aps = config.wifi;
        try {
            Class<?> manager = XposedHelpers.findClass("android.net.wifi.WifiManager", loader);
            XposedBridge.hookAllMethods(manager, "getScanResults", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!active()) {
                        return;
                    }
                    scanServed.incrementAndGet();
                    param.setResult(scanResults(aps));
                }
            });
            XposedBridge.hookAllMethods(manager, "getConnectionInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!active()) {
                        return;
                    }
                    connectionServed.incrementAndGet();
                    param.setResult(connectionInfo(aps));
                }
            });
            XposedBridge.hookAllMethods(manager, "isWifiEnabled", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (active()) {
                        param.setResult(true);
                    }
                }
            });
            status = "ok";
            XposedBridge.log("ClaudeWilsonHook wifi status=ok aps=" + aps.size());
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook wifi: " + t);
        }
    }

    private static boolean active() {
        Context context = HookContext.app(classLoader);
        if (context == null) {
            return false;
        }
        return permitted(context);
    }

    private static boolean permitted(Context context) {
        if (context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == 0) {
            return true;
        }
        return Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") == 0;
    }

    private static List<ScanResult> scanResults(List<WifiProfile.Ap> aps) {
        List<ScanResult> out = new ArrayList<>();
        long timestamp = SystemClock.elapsedRealtimeNanos() / 1000L;
        for (WifiProfile.Ap ap : aps) {
            try {
                ScanResult result = new ScanResult();
                result.SSID = ap.ssid;
                result.BSSID = ap.bssid;
                result.frequency = ap.frequency;
                result.level = ap.level;
                result.capabilities = ap.capabilities;
                result.timestamp = timestamp;
                emptyInformationElements(result);
                out.add(result);
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook wifi scan row: " + t);
            }
        }
        return out;
    }

    private static void emptyInformationElements(ScanResult result) throws Exception {
        Field field = ScanResult.class.getDeclaredField("informationElements");
        field.setAccessible(true);
        if (field.get(result) == null) {
            Class<?> type = field.getType();
            if (type.isArray()) {
                field.set(result, Array.newInstance(type.getComponentType(), 0));
            }
        }
    }

    private static WifiInfo connectionInfo(List<WifiProfile.Ap> aps) {
        WifiProfile.Ap connected = null;
        for (WifiProfile.Ap ap : aps) {
            if (ap.connected) {
                connected = ap;
                break;
            }
        }
        if (connected == null) {
            connected = aps.get(0);
        }
        try {
            WifiInfo.Builder builder = new WifiInfo.Builder()
                    .setSsid(connected.ssid.getBytes(StandardCharsets.UTF_8))
                    .setBssid(connected.bssid)
                    .setNetworkId(0)
                    .setRssi(connected.level);
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setCurrentSecurityType(2);
            }
            WifiInfo info = builder.build();
            int speed = connected.frequency >= 5925 ? 1200 : 144;
            set(info, "mFrequency", connected.frequency);
            set(info, "mLinkSpeed", speed);
            set(info, "mTxLinkSpeed", speed);
            set(info, "mRxLinkSpeed", speed);
            set(info, "mMaxSupportedTxLinkSpeed", speed);
            set(info, "mMaxSupportedRxLinkSpeed", speed);
            set(info, "mWifiStandard", connected.frequency >= 5925 ? 6 : 5);
            set(info, "mSupplicantState", SupplicantState.COMPLETED);
            set(info, "mHiddenSSID", false);
            set(info, "mIsCarrierMerged", false);
            set(info, "mSubscriptionId", -1);
            set(info, "mMacAddress", "02:00:00:00:00:00");
            return info;
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook wifi info: " + t);
            return null;
        }
    }

    private static void set(WifiInfo info, String name, Object value) {
        try {
            Field field = WifiInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(info, value);
        } catch (Throwable ignored) {
        }
    }
}
