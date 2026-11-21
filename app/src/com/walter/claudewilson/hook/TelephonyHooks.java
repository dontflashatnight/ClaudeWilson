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
            if (!config.keepRealSim) {
                hook(trace, tm, "getSimOperator", config.simOperator);
                hook(trace, tm, "getSimCountryIso", config.simCountryIso);
                hook(trace, tm, "getSimOperatorName", config.simOperatorName);
            }
            hook(trace, tm, "getNetworkOperator", config.netOperator);
            hook(trace, tm, "getNetworkCountryIso", config.netCountryIso);
            hook(trace, tm, "getNetworkOperatorName", config.netOperatorName);
            boolean roaming = !config.keepRealSim
                    && !config.simCountryIso.isEmpty()
                    && !config.netCountryIso.isEmpty()
                    && !config.simCountryIso.equalsIgnoreCase(config.netCountryIso);
            hook(trace, tm, "isNetworkRoaming", roaming);
            if (!config.keepRealSim) {
                hook(trace, tm, "getSimCarrierId", config.simCarrierId);
                hook(trace, tm, "getSimSpecificCarrierId", config.simCarrierId);
                try {
                    XC_MethodHook carrierNameHook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(config.simCarrierId >= 0
                                    ? config.simOperatorName : null);
                        }
                    };
                    XposedBridge.hookAllMethods(tm, "getSimCarrierIdName", carrierNameHook);
                    XposedBridge.hookAllMethods(tm, "getSimSpecificCarrierIdName", carrierNameHook);
                } catch (Throwable t) {
                    trace.append("carrierName:").append(t).append(';');
                }
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
                XposedBridge.hookAllMethods(tm, "requestCellInfoUpdate",
                        new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2) return;
                        final java.util.concurrent.Executor exec =
                                param.args[0] instanceof java.util.concurrent.Executor
                                        ? (java.util.concurrent.Executor) param.args[0] : null;
                        final Object cb = param.args[1];
                        if (cb == null) return;
                        param.setResult(null);
                        Runnable deliver = new Runnable() {
                            @Override public void run() {
                                try {
                                    XposedHelpers.callMethod(cb, "onCellInfo",
                                            new ArrayList<Object>());
                                } catch (Throwable ignored) {
                                }
                            }
                        };
                        if (exec != null) {
                            try {
                                exec.execute(deliver);
                            } catch (Throwable ignored) {
                            }
                        } else {
                            deliver.run();
                        }
                    }
                });
            } catch (Throwable t) {
                trace.append("cell:").append(t).append(';');
            }
            installRoamingHooks(loader, trace, roaming);
            installServiceStateIdentityHooks(loader, trace, config);
        } catch (Throwable t) {
            trace.append("tm:").append(t).append(';');
        }

        if (!config.keepRealSim) {
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
        }
        status = trace.length() == 0
                ? (config.keepRealSim ? "ok-real-sim" : "ok")
                : trace.toString();
        if (HookEntry.debugLog) {
            XposedBridge.log("ClaudeWilsonHook telephony status=" + status);
        }
    }

    /** Aligns ServiceState operator strings and blanks NetworkRegistrationInfo CellIdentity. */
    private static void installServiceStateIdentityHooks(ClassLoader loader, StringBuilder trace,
                                                         HookConfig config) {
        if (!config.netOperatorName.isEmpty()) {
            hookOptional(loader, trace, "android.telephony.ServiceState",
                    "getOperatorAlphaLong", config.netOperatorName);
            hookOptional(loader, trace, "android.telephony.ServiceState",
                    "getOperatorAlphaShort", config.netOperatorName);
        }
        if (!config.netOperator.isEmpty()) {
            hookOptional(loader, trace, "android.telephony.ServiceState",
                    "getOperatorNumeric", config.netOperator);
        }
        hookOptional(loader, trace, "android.telephony.NetworkRegistrationInfo",
                "getCellIdentity", null);
    }

    /** Defensive roaming-state hooks across SubscriptionManager, ServiceState and NRI. */
    private static void installRoamingHooks(ClassLoader loader, StringBuilder trace,
                                            boolean roaming) {
        hookOptional(loader, trace, "android.telephony.SubscriptionManager",
                "isNetworkRoaming", roaming);
        hookOptional(loader, trace, "android.telephony.ServiceState",
                "getRoaming", roaming);
        hookOptional(loader, trace, "android.telephony.ServiceState",
                "getVoiceRoaming", roaming);
        hookOptional(loader, trace, "android.telephony.ServiceState",
                "getDataRoaming", roaming);
        hookOptional(loader, trace, "android.telephony.NetworkRegistrationInfo",
                "isRoaming", roaming);
    }

    private static void hookOptional(ClassLoader loader, StringBuilder trace,
                                     String className, String methodName, Object value) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, loader);
            if (clazz == null) {
                return;
            }
            if (XposedBridge.hookAllMethods(clazz, methodName,
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            }).isEmpty()) {
                return;
            }
        } catch (Throwable t) {
            trace.append(methodName).append(':').append(t).append(';');
        }
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
