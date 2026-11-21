package com.walter.claudewilson.hook;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
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
        classLoader = loader;
        installCountryCodeHook(loader, config);
        if (config.wifi.isEmpty()) {
            status = "off";
            return;
        }
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
            Class<?> caps = XposedHelpers.findClassIfExists(
                    "android.net.NetworkCapabilities", loader);
            if (caps != null) {
                XposedBridge.hookAllMethods(caps, "getTransportInfo", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object original = param.getResult();
                        if (!(original instanceof WifiInfo) || !active()) {
                            return;
                        }
                        WifiInfo spoofed = connectionInfo(aps);
                        if (spoofed != null) {
                            connectionServed.incrementAndGet();
                            param.setResult(spoofed);
                        }
                    }
                });
            }
            installRttAndP2pGuards(loader);
            status = "ok";
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook wifi status=ok aps=" + aps.size());
            }
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook wifi: " + t);
        }
    }

    /** Aligns Wi-Fi chip regulatory country code (e.g. prevents leaking "CN" on a JP persona). */
    private static void installCountryCodeHook(ClassLoader loader, HookConfig config) {
        String iso = config.countryCodeHint != null && config.countryCodeHint.matches("[A-Za-z]{2}")
                ? config.countryCodeHint
                : (config.simCountryIso != null && config.simCountryIso.matches("[A-Za-z]{2}")
                ? config.simCountryIso : "");
        if (iso.isEmpty()) {
            return;
        }
        final String upperIso = iso.toUpperCase(java.util.Locale.ROOT);
        try {
            Class<?> manager = XposedHelpers.findClass("android.net.wifi.WifiManager", loader);
            XposedBridge.hookAllMethods(manager, "getCountryCode",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(upperIso);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Defensive guards against Wi-Fi RTT indoor ranging and Wi-Fi Direct peer leaks. */
    private static void installRttAndP2pGuards(ClassLoader loader) {
        try {
            Class<?> rtt = XposedHelpers.findClassIfExists(
                    "android.net.wifi.rtt.WifiRttManager", loader);
            if (rtt != null) {
                XposedBridge.hookAllMethods(rtt, "startRanging", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!active() || param.args == null) return;
                        java.util.concurrent.Executor exec = null;
                        Object cb = null;
                        for (Object a : param.args) {
                            if (a instanceof java.util.concurrent.Executor) {
                                exec = (java.util.concurrent.Executor) a;
                            } else if (a != null && a.getClass().getName().contains("RangingResultCallback")) {
                                cb = a;
                            }
                        }
                        if (cb == null) return;
                        param.setResult(null);
                        final Object fcb = cb;
                        Runnable fail = new Runnable() {
                            @Override public void run() {
                                try {
                                    XposedHelpers.callMethod(fcb, "onRangingFailure", 1);
                                } catch (Throwable ignored) {
                                }
                            }
                        };
                        if (exec != null) {
                            try { exec.execute(fail); } catch (Throwable ignored) {}
                        } else {
                            fail.run();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> p2p = XposedHelpers.findClassIfExists(
                    "android.net.wifi.p2p.WifiP2pManager", loader);
            if (p2p != null) {
                XposedBridge.hookAllMethods(p2p, "requestPeers", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!active() || param.args == null || param.args.length < 2) return;
                        Object listener = param.args[1];
                        if (listener == null) return;
                        try {
                            Class<?> listCls = XposedHelpers.findClassIfExists(
                                    "android.net.wifi.p2p.WifiP2pDeviceList", loader);
                            if (listCls != null) {
                                Object empty = listCls.getDeclaredConstructor().newInstance();
                                param.setResult(null);
                                XposedHelpers.callMethod(listener, "onPeersAvailable", empty);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
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
        return HookContext.HOST_SDK_INT >= 33
                && context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") == 0;
    }

    @SuppressWarnings("deprecation")
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
                setWifiSsid(result, ap.ssid);
                emptyInformationElements(result);
                out.add(result);
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook wifi scan row: " + t);
            }
        }
        return out;
    }

    private static void setWifiSsid(ScanResult result, String ssid) {
        if (HookContext.HOST_SDK_INT < 33 || ssid == null) {
            return;
        }
        try {
            Class<?> ssidClass = XposedHelpers.findClassIfExists(
                    "android.net.wifi.WifiSsid", classLoader);
            if (ssidClass == null) {
                return;
            }
            Object wifiSsid = XposedHelpers.callStaticMethod(
                    ssidClass, "fromBytes", ssid.getBytes(StandardCharsets.UTF_8));
            if (wifiSsid == null) {
                return;
            }
            if (XposedHelpers.findFieldIfExists(ScanResult.class, "wifiSsid") != null) {
                XposedHelpers.setObjectField(result, "wifiSsid", wifiSsid);
            } else {
                XposedHelpers.callMethod(result, "setWifiSsid", wifiSsid);
            }
        } catch (Throwable ignored) {
        }
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
            if (HookContext.HOST_SDK_INT >= 31) {
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
