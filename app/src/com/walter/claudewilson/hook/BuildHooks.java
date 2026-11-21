package com.walter.claudewilson.hook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Build fields are frozen at zygote start; the module rewrites them before app code runs. */
final class BuildHooks {
    private BuildHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        try {
            Class<?> build = XposedHelpers.findClass("android.os.Build", loader);
            Class<?> version = XposedHelpers.findClass("android.os.Build$VERSION", loader);
            boolean sdkMismatch = false;
            if (config.build.containsKey("VERSION.SDK_INT")) {
                String rawSdk = config.build.get("VERSION.SDK_INT");
                int payloadSdk = parseSdk(rawSdk);
                if (payloadSdk != HookContext.HOST_SDK_INT) {
                    sdkMismatch = true;
                    if (HookEntry.debugLog) {
                        XposedBridge.log("ClaudeWilsonHook build.SDK_INT anchored to host="
                                + HookContext.HOST_SDK_INT + " (ignored payload=" + rawSdk + ")");
                    }
                }
            }
            for (MapEntry entry : entries(config)) {
                try {
                    if (entry.version) XposedHelpers.setStaticObjectField(version, entry.key, entry.value);
                    else setOptionalStaticString(build, entry.key, entry.value);
                } catch (Throwable t) {
                    if (HookEntry.debugLog) {
                        XposedBridge.log("ClaudeWilsonHook build." + entry.key + ": " + t);
                    }
                }
            }
            if (config.build.containsKey("VERSION.RELEASE") || sdkMismatch) {
                try {
                    XposedHelpers.setStaticObjectField(version, "RELEASE", HookContext.HOST_RELEASE);
                } catch (Throwable t) {
                    XposedBridge.log("ClaudeWilsonHook build.RELEASE: " + t);
                }
                setOptionalStaticString(version, "RELEASE_OR_CODENAME",
                        HookContext.HOST_RELEASE_OR_CODENAME);
                setOptionalStaticString(version, "RELEASE_OR_PREVIEW_DISPLAY",
                        HookContext.HOST_RELEASE_OR_CODENAME);
            }
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook build hooks: " + t);
        }
    }

    /**
     * Rewrites the {@code :release/} segment of a canonical build fingerprint to match
     * {@code release}. Malformed strings are returned unchanged.
     */
    static String rewriteFingerprintRelease(String fingerprint, String release) {
        if (fingerprint == null || release == null || release.isEmpty()) {
            return fingerprint;
        }
        int colon = fingerprint.indexOf(':');
        if (colon <= 0) {
            return fingerprint;
        }
        int slash = fingerprint.indexOf('/', colon + 1);
        if (slash <= colon + 1) {
            return fingerprint;
        }
        String current = fingerprint.substring(colon + 1, slash);
        if (release.equals(current)) {
            return fingerprint;
        }
        return fingerprint.substring(0, colon + 1) + release + fingerprint.substring(slash);
    }

    private static int parseSdk(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void setOptionalStaticString(Class<?> clazz, String fieldName, String value) {
        try {
            if (XposedHelpers.findFieldIfExists(clazz, fieldName) != null) {
                XposedHelpers.setStaticObjectField(clazz, fieldName, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.util.List<MapEntry> entries(HookConfig config) {
        java.util.List<MapEntry> out = new java.util.ArrayList<>();
        String[] buildFields = {"MANUFACTURER", "BRAND", "MODEL", "DEVICE", "PRODUCT",
                "HARDWARE", "BOARD", "SOC_MANUFACTURER", "SOC_MODEL", "SKU",
                "FINGERPRINT", "ID", "TAGS", "TYPE", "DISPLAY"};
        for (String key : buildFields) {
            if (config.build.containsKey(key)) {
                String value = config.build.get(key);
                if ("FINGERPRINT".equals(key)) {
                    value = rewriteFingerprintRelease(value, HookContext.HOST_RELEASE);
                }
                out.add(new MapEntry(key, value, false));
            }
        }
        String[] versionFields = {"RELEASE", "SECURITY_PATCH", "INCREMENTAL"};
        for (String key : versionFields) {
            String value = config.build.get("VERSION." + key);
            if (value != null) {
                if ("RELEASE".equals(key)) {
                    value = HookContext.HOST_RELEASE;
                }
                out.add(new MapEntry(key, value, true));
            }
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
