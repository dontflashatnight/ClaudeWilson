package com.walter.claudewilson.hook;

import android.os.Build;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Build fields are frozen at zygote start; the module rewrites them before app code runs. */
final class BuildHooks {
    private BuildHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        try {
            Class<?> build = XposedHelpers.findClass("android.os.Build", loader);
            Class<?> version = XposedHelpers.findClass("android.os.Build$VERSION", loader);
            for (MapEntry entry : entries(config)) {
                try {
                    if (entry.version) XposedHelpers.setStaticObjectField(version, entry.key, entry.value);
                    else XposedHelpers.setStaticObjectField(build, entry.key, entry.value);
                } catch (Throwable t) {
                    XposedBridge.log("ClaudeWilsonHook build." + entry.key + ": " + t);
                }
            }
            if (config.build.containsKey("VERSION.SDK_INT")) {
                try {
                    XposedHelpers.setStaticIntField(version, "SDK_INT",
                            Integer.parseInt(config.build.get("VERSION.SDK_INT")));
                } catch (Throwable t) {
                    XposedBridge.log("ClaudeWilsonHook build.SDK_INT: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook build hooks: " + t);
        }
    }

    private static java.util.List<MapEntry> entries(HookConfig config) {
        java.util.List<MapEntry> out = new java.util.ArrayList<>();
        String[] buildFields = {"MANUFACTURER", "BRAND", "MODEL", "DEVICE", "PRODUCT",
                "HARDWARE", "BOARD", "FINGERPRINT", "ID", "TAGS", "TYPE", "DISPLAY"};
        for (String key : buildFields) {
            if (config.build.containsKey(key)) {
                out.add(new MapEntry(key, config.build.get(key), false));
            }
        }
        String[] versionFields = {"RELEASE", "SECURITY_PATCH", "INCREMENTAL"};
        for (String key : versionFields) {
            String value = config.build.get("VERSION." + key);
            if (value != null) out.add(new MapEntry(key, value, true));
        }
        return out;
    }

    private static final class MapEntry {
        final String key;
        final String value;
        final boolean version;

        MapEntry(String key, String value, boolean version) {
            this.key = key;
            this.value = value;
            this.version = version;
        }
    }
}
