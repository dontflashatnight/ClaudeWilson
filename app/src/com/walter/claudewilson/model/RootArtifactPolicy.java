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
    };
    private static final String[] TREES = {
            "/data/adb/ksu",
            "/data/adb/magisk",
            "/data/adb/modules",
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

    private static String base(String value) {
        if (value == null) {
            return "";
        }
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
