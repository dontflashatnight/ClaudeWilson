package com.walter.claudewilson.model;

import java.util.List;

/**
 * Target-side root-artifact policy, kept free of Android and Xposed so it can be tested offline.
 *
 * <p>The hook layer only implements the mechanism; this class owns which paths are hidden and
 * which command shapes count as an su probe. Both are deliberately narrow.
 */
public final class RootArtifactPolicy {
    private static final String[] EXACT = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/cache/magisk.log",
            "/data/magisk",
            "/data/local/tmp/persona.json",
    };
    private static final String[] TREES = {
            "/data/adb/ksu",
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/local/tmp/claudewilson",
            "/data/local/tmp/frida",
    };
    private static final String[] HIDDEN_PACKAGES = {
            "com.walter.claudewilson",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "moe.shizuku.privileged.api",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "com.amphoras.hidemyroot",
            "com.formyhm.hideroot",
    };

    private RootArtifactPolicy() {
    }

    public static boolean hidden(String path) {
        if (path == null) {
            return false;
        }
        String value = path.replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        for (String exact : EXACT) {
            if (exact.equals(value)) {
                return true;
            }
        }
        for (String tree : TREES) {
            if (tree.equals(value) || value.startsWith(tree + "/")) {
                return true;
            }
        }
        return isInternalResiduePath(value);
    }

    private static boolean isInternalResiduePath(String normalizedPath) {
        if (normalizedPath.contains("/cw-telemetry/") || normalizedPath.endsWith("/cw-telemetry")) {
            return true;
        }
        String name = base(normalizedPath);
        if ("cw-telemetry".equals(name) || "hookcheck.txt".equals(name)
                || "cw-hookcheck.txt".equals(name)) {
            return true;
        }
        return (name.startsWith("hookcheck.") || name.startsWith("cw-hookcheck."))
                && name.endsWith(".txt");
    }

    /** True when the package is this control app or a known root/hook manager to hide. */
    public static boolean hiddenPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        for (String pkg : HIDDEN_PACKAGES) {
            if (pkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** Matches a command that invokes su, or probes for it via which/command/type. */
    public static boolean isSuCommand(Object command) {
        if (command instanceof String) {
            return commandLine((String) command);
        }
        if (command instanceof String[]) {
            return tokens((String[]) command);
        }
        if (command instanceof List) {
            List<?> list = (List<?>) command;
            String[] values = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                values[i] = String.valueOf(list.get(i));
            }
            return tokens(values);
        }
        return false;
    }

    private static boolean commandLine(String command) {
        if (command == null) {
            return false;
        }
        if (command.contains("/system/bin/su") || command.contains("/system/xbin/su")
                || command.contains("/sbin/su")) {
            return true;
        }
        return tokens(command.trim().split("\\s+"));
    }

    private static boolean tokens(String[] values) {
        if (values == null || values.length == 0) {
            return false;
        }
        String first = base(values[0]);
        if ("su".equals(first)) {
            return true;
        }
        for (int i = 0; i + 1 < values.length; i++) {
            String verb = base(values[i]);
            if (("which".equals(verb) || "command".equals(verb) || "type".equals(verb)
                    || "whereis".equals(verb)) && "su".equals(base(values[i + 1]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a {@code Runtime.exec} / {@code ProcessBuilder} command if it invokes {@code getprop}.
     *
     * @return {@code null} when not a {@code getprop} command, {@code ""} for bare {@code getprop}
     *         (full dump), or the requested property key name for {@code getprop <key>}.
     */
    public static String parseGetpropCommand(Object command) {
        String[] parts = toTokens(command);
        if (parts == null || parts.length == 0) {
            return null;
        }
        if (!"getprop".equals(base(parts[0]))) {
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            String tok = parts[i] == null ? "" : parts[i].trim();
            if (!tok.isEmpty() && !tok.startsWith("-")) {
                return tok;
            }
        }
        return "";
    }

    /** True when a command invokes bare {@code mount} (used by RootBeer RW-path checks). */
    public static boolean isMountCommand(Object command) {
        String[] parts = toTokens(command);
        return parts != null && parts.length == 1 && "mount".equals(base(parts[0]));
    }

    private static String[] toTokens(Object command) {
        if (command instanceof String) {
            String s = ((String) command).trim();
            return s.isEmpty() ? null : s.split("\\s+");
        }
        if (command instanceof String[]) {
            return (String[]) command;
        }
        if (command instanceof List) {
            List<?> list = (List<?>) command;
            String[] values = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                values[i] = list.get(i) == null ? "" : String.valueOf(list.get(i));
            }
            return values;
        }
        return null;
    }

    private static String base(String value) {
        if (value == null) {
            return "";
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
