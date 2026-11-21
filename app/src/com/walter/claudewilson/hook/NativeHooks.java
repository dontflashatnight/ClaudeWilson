package com.walter.claudewilson.hook;

import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/** Loads the native libc/proc cloak and passes it the persona property map. */
final class NativeHooks {
    private static volatile boolean attempted;
    private static volatile boolean ready;

    private NativeHooks() {}

    static void install(HookConfig config) {
        if (attempted) return;
        attempted = true;
        try {
            System.loadLibrary("cwpersona");
            Map<String, String> resolved = SystemPropertiesHooks.resolvedMap(config);
            String[] keys = new String[resolved.size()];
            String[] values = new String[keys.length];
            int i = 0;
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                i++;
            }
            configure(keys, values);
            java.net.InetAddress v6 = NetworkHooks.spoofForDefaultInterface();
            if (v6 instanceof java.net.Inet6Address) {
                configureIpv6(v6.getAddress());
            } else {
                configureIpv6(null);
            }
            if (config != null && config.hiddenIfaces != null && !config.hiddenIfaces.isEmpty()) {
                try {
                    configureHiddenIfaces(config.hiddenIfaces.toArray(new String[0]));
                } catch (UnsatisfiedLinkError ignored) {
                    // Compatible with prebuilt libcwpersona.so before native rebuild.
                }
            }
            ready = ready();
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook native=" + ready);
            }
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook native load: " + t);
            }
        }
    }

    static boolean isReady() {
        return ready;
    }

    private static native void configure(String[] keys, String[] values);

    private static native void configureIpv6(byte[] addr16);

    private static native void configureHiddenIfaces(String[] ifaces);

    private static native boolean ready();
}
