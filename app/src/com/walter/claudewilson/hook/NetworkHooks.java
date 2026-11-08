package com.walter.claudewilson.hook;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hides tunnel interfaces, VPN transport and system proxy from the target process. */
final class NetworkHooks {
    private static final int TRANSPORT_VPN = 4;
    private static final int CAPABILITY_NOT_VPN = 10;

    private NetworkHooks() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> ni = XposedHelpers.findClass("java.net.NetworkInterface", loader);
            XposedBridge.hookAllMethods(ni, "getNetworkInterfaces", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Enumeration)) return;
                    List<NetworkInterface> keep = new ArrayList<>();
                    Enumeration<?> all = (Enumeration<?>) result;
                    while (all.hasMoreElements()) {
                        Object next = all.nextElement();
                        if (next instanceof NetworkInterface
                                && !badName(((NetworkInterface) next).getName())) {
                            keep.add((NetworkInterface) next);
                        }
                    }
                    param.setResult(Collections.enumeration(keep));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook interfaces: " + t);
        }

        try {
            Class<?> lp = XposedHelpers.findClass("android.net.LinkProperties", loader);
            XposedBridge.hookAllMethods(lp, "getInterfaceName", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (value instanceof String && badName((String) value)) {
                        param.setResult("wlan0");
                    }
                }
            });
            XposedBridge.hookAllMethods(lp, "getHttpProxy", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    return null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook linkproperties: " + t);
        }

        try {
            Class<?> nc = XposedHelpers.findClass("android.net.NetworkCapabilities", loader);
            XposedBridge.hookAllMethods(nc, "hasTransport", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && Integer.valueOf(TRANSPORT_VPN)
                            .equals(param.args[0])) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.hookAllMethods(nc, "hasCapability", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && Integer.valueOf(CAPABILITY_NOT_VPN)
                            .equals(param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.hookAllMethods(nc, "getTransportTypes", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object value = param.getResult();
                    if (!(value instanceof int[])) return;
                    int[] types = (int[]) value;
                    int count = 0;
                    for (int type : types) if (type != TRANSPORT_VPN) count++;
                    int[] out = new int[count];
                    int i = 0;
                    for (int type : types) if (type != TRANSPORT_VPN) out[i++] = type;
                    param.setResult(out);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook networkcaps: " + t);
        }

        try {
            Class<?> cm = XposedHelpers.findClass(
                    "android.net.ConnectivityManager", loader);
            XposedBridge.hookAllMethods(cm, "getDefaultProxy", new XC_MethodReplacement() {
                @Override protected Object replaceHookedMethod(MethodHookParam param) {
                    return null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook proxy: " + t);
        }

        try {
            XposedBridge.hookAllMethods(System.class, "getProperty", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0) return;
                    String key = String.valueOf(param.args[0]);
                    if (key.matches("(?i)^(https?|socks)\\.proxy.*")) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook sysprops: " + t);
        }
    }

    private static boolean badName(String name) {
        return name != null && name.matches("^(tun|ppp|wg|tap|utun).*");
    }
}
