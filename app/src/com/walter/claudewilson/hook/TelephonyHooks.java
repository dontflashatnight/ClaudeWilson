package com.walter.claudewilson.hook;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Java-visible telephony identity, including the dual-SIM SubscriptionInfo objects. */
final class TelephonyHooks {
    static volatile String status = "not-run";

    private TelephonyHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        StringBuilder trace = new StringBuilder();
        try {
            Class<?> tm = XposedHelpers.findClass("android.telephony.TelephonyManager", loader);
            hook(trace, tm, "getSimOperator", config.simOperator);
            hook(trace, tm, "getSimCountryIso", config.simCountryIso);
            hook(trace, tm, "getSimOperatorName", config.simOperatorName);
            hook(trace, tm, "getNetworkOperator", config.netOperator);
            hook(trace, tm, "getNetworkCountryIso", config.netCountryIso);
            hook(trace, tm, "getNetworkOperatorName", config.netOperatorName);
            hook(trace, tm, "getSimCarrierId", config.simCarrierId);
            try {
                XposedBridge.hookAllMethods(tm, "getSimCarrierIdName",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(config.simCarrierId >= 0
                                ? config.simOperatorName : null);
                    }
                });
            } catch (Throwable t) {
                trace.append("carrierName:").append(t).append(';');
            }
            try {
                XposedBridge.hookAllMethods(tm, "getAllCellInfo",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(new ArrayList<Object>());
                    }
                });
                XposedBridge.hookAllMethods(tm, "getCellLocation",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(null);
                    }
                });
            } catch (Throwable t) {
                trace.append("cell:").append(t).append(';');
            }
        } catch (Throwable t) {
            trace.append("tm:").append(t).append(';');
        }

        try {
            Class<?> si = XposedHelpers.findClass("android.telephony.SubscriptionInfo", loader);
            int mcc = parse(config.simOperator.substring(0, 3));
            int mnc = parse(config.simOperator.substring(3));
            hook(trace, si, "getMcc", mcc);
            hook(trace, si, "getMnc", mnc);
            hook(trace, si, "getMccString", config.simOperator.substring(0, 3));
            hook(trace, si, "getMncString", config.simOperator.substring(3));
            hook(trace, si, "getCountryIso", config.simCountryIso);
            hook(trace, si, "getCarrierName", config.simOperatorName);
            hook(trace, si, "getDisplayName", config.simOperatorName);
            hook(trace, si, "getSimOperatorName", config.simOperatorName);
        } catch (Throwable t) {
            trace.append("si:").append(t).append(';');
        }
        status = trace.length() == 0 ? "ok" : trace.toString();
        XposedBridge.log("ClaudeWilsonHook telephony status=" + status);
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); } catch (Throwable t) { return 0; }
    }

    private static void hook(StringBuilder trace, Class<?> clazz, String name, Object value) {
        try {
            XposedBridge.hookAllMethods(clazz, name,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
        } catch (Throwable t) {
            trace.append(name).append(':').append(t).append(';');
            XposedBridge.log("ClaudeWilsonHook telephony." + name + ": " + t);
        }
    }
}
