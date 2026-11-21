package com.walter.claudewilson.hook;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Java-side property reads; native property reads are a separate layer. */
final class SystemPropertiesHooks {
    private static final String PROP_SDK = "ro.build.version.sdk";
    private static final String PROP_SDK_FULL = "ro.build.version.sdk_full";
    private static final String PROP_RELEASE = "ro.build.version.release";
    private static final String PROP_RELEASE_OR_CODENAME = "ro.build.version.release_or_codename";
    private static final String PROP_RELEASE_OR_PREVIEW =
            "ro.build.version.release_or_preview_display";

    private SystemPropertiesHooks() {}

    static void install(ClassLoader loader, HookConfig config) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", loader);
            XposedBridge.hookAllMethods(sp, "get",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    String key = String.valueOf(param.args[0]);
                    String value = resolve(config, key);
                    if (value != null) {
                        if (value.isEmpty() && param.args.length >= 2) {
                            param.setResult(param.args[1]);
                        } else {
                            param.setResult(value);
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(sp, "getInt",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || param.args[0] == null) return;
                    String key = String.valueOf(param.args[0]);
                    String value = resolve(config, key);
                    if (value != null) {
                        int def = param.args[1] instanceof Number
                                ? ((Number) param.args[1]).intValue() : 0;
                        param.setResult(parseInt(value, def));
                    }
                }
            });
            XposedBridge.hookAllMethods(sp, "getLong",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || param.args[0] == null) return;
                    String key = String.valueOf(param.args[0]);
                    String value = resolve(config, key);
                    if (value != null) {
                        long def = param.args[1] instanceof Number
                                ? ((Number) param.args[1]).longValue() : 0L;
                        param.setResult(parseLong(value, def));
                    }
                }
            });
            XposedBridge.hookAllMethods(sp, "getBoolean",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || param.args[0] == null) return;
                    String key = String.valueOf(param.args[0]);
                    String value = resolve(config, key);
                    if (value != null) {
                        boolean def = param.args[1] instanceof Boolean
                                && (Boolean) param.args[1];
                        param.setResult(parseBoolean(value, def));
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook systemproperties: " + t);
        }
    }

    /**
     * Resolves the full anchored property table for native Bionic hooks so Java and libc
     * property layers never disagree on SDK/release/fingerprint values.
     */
    static Map<String, String> resolvedMap(HookConfig config) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : config.props.keySet()) {
            String value = resolve(config, key);
            if (value != null) {
                out.put(key, value);
            }
        }
        out.put(PROP_SDK, String.valueOf(HookContext.HOST_SDK_INT));
        String sdkFull = HookContext.hostSdkFullProperty();
        if (sdkFull != null) {
            out.put(PROP_SDK_FULL, sdkFull);
        } else {
            out.remove(PROP_SDK_FULL);
        }
        if (config.props.containsKey(PROP_RELEASE)) {
            out.put(PROP_RELEASE, HookContext.HOST_RELEASE);
            out.put(PROP_RELEASE_OR_CODENAME, HookContext.HOST_RELEASE_OR_CODENAME);
            out.put(PROP_RELEASE_OR_PREVIEW, HookContext.HOST_RELEASE_OR_CODENAME);
        }
        return out;
    }

    static String resolve(HookConfig config, String key) {
        if (PROP_SDK.equals(key)) {
            return String.valueOf(HookContext.HOST_SDK_INT);
        }
        if (PROP_SDK_FULL.equals(key)) {
            return HookContext.hostSdkFullProperty();
        }
        if (PROP_RELEASE.equals(key)) {
            return config.props.containsKey(PROP_RELEASE) ? HookContext.HOST_RELEASE : null;
        }
        if (PROP_RELEASE_OR_CODENAME.equals(key) || PROP_RELEASE_OR_PREVIEW.equals(key)) {
            return (config.props.containsKey(PROP_RELEASE) || config.props.containsKey(key))
                    ? HookContext.HOST_RELEASE_OR_CODENAME : null;
        }
        String value = config.props.get(key);
        if (value != null && key.endsWith(".build.fingerprint")) {
            return BuildHooks.rewriteFingerprintRelease(value, HookContext.HOST_RELEASE);
        }
        return value;
    }

    private static int parseInt(String value, int def) {
        long parsed = parseBionicLong(value, def, true);
        return parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE ? def : (int) parsed;
    }

    private static long parseLong(String value, long def) {
        return parseBionicLong(value, def, false);
    }

    /** Matches Bionic strtol/strtoll(..., 0): optional sign, 0x/0X hex, 0-prefix octal, else decimal. */
    private static long parseBionicLong(String value, long def, boolean asInt) {
        String s = value.trim();
        if (s.isEmpty()) return def;
        boolean negative = false;
        int pos = 0;
        char first = s.charAt(0);
        if (first == '-' || first == '+') {
            negative = first == '-';
            pos = 1;
            if (pos >= s.length()) return def;
        }
        int radix = 10;
        if (s.charAt(pos) == '0') {
            if (pos + 1 < s.length() && (s.charAt(pos + 1) == 'x' || s.charAt(pos + 1) == 'X')) {
                radix = 16;
                pos += 2;
                if (pos >= s.length()) return def;
            } else if (pos + 1 < s.length()) {
                radix = 8;
                pos += 1;
            }
        }
        String digits = (negative ? "-" : "") + s.substring(pos);
        try {
            return asInt ? Integer.parseInt(digits, radix) : Long.parseLong(digits, radix);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static boolean parseBoolean(String value, boolean def) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(lower) || "y".equals(lower) || "yes".equals(lower)
                || "on".equals(lower) || "true".equals(lower)) {
            return true;
        }
        if ("0".equals(lower) || "n".equals(lower) || "no".equals(lower)
                || "off".equals(lower) || "false".equals(lower)) {
            return false;
        }
        return def;
    }
}
