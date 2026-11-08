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
            String[] keys = new String[config.props.size()];
            String[] values = new String[keys.length];
            int i = 0;
            for (Map.Entry<String, String> entry : config.props.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                i++;
            }
            configure(keys, values);
            ready = ready();
            XposedBridge.log("ClaudeWilsonHook native=" + ready);
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook native load: " + t);
        }
    }

    static boolean isReady() {
        return ready;
    }

    private static native void configure(String[] keys, String[] values);

    private static native boolean ready();
}
