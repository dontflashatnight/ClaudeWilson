package com.walter.claudewilson;

import android.content.Context;
import android.util.Base64;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

/** Device-side control plane for the Vector module: config file and target self-check. */
final class RootActions {
    private static final String CONFIG_DIR = "/data/local/tmp/claudewilson";

    private final Context context;

    RootActions(Context context) { this.context = context.getApplicationContext(); }

    /**
     * Opens the system SIM manager.
     *
     * <p>{@code android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS} resolves to the vendor's SIM
     * manager on One UI; the network-operator screen is the fallback on builds without that
     * activity.
     */
    static void openSimManager() {
        String out = RootShell.run(
                "am start -a android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS 2>&1");
        if (out == null || out.contains("Error") || out.contains("does not exist")
                || out.contains("Exception")) {
            RootShell.run("am start -a android.settings.NETWORK_OPERATOR_SETTINGS");
        }
    }

    /**
     * Publishes the persona payload to the selected target. The target reads this file on its
     * next process start; no pid, no attach and no spawn are involved.
     */
    String publish(TargetSpec target, String json) {
        if (target == null || !target.hooksEnabled()) {
            return "refused: target is diagnostic-only";
        }
        if (json == null || json.isEmpty()) {
            return "refused: empty persona";
        }
        String encoded = Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String path = target.configPath();
        // base64 is available in Android's toybox on every supported release. The JSON itself is
        // never interpolated into the shell; only the base64 alphabet is.
        String script = "mkdir -p " + CONFIG_DIR
                + " && printf %s '" + encoded + "' | base64 -d > " + path
                + " && chmod 644 " + path
                + " && sync";
        return RootShell.runCapture(script);
    }

    /** Removes one target's payload. The legacy path is cleared only for the target that had it. */
    String clearConfig(TargetSpec target) {
        if (target == null) {
            return "refused: no target";
        }
        String script = "rm -f " + target.configPath();
        if (!target.legacyConfigPath().isEmpty()) {
            script += " " + target.legacyConfigPath();
        }
        return RootShell.runCapture(script + " && sync");
    }

    /** True when a canonical payload, or a legacy payload for this target, is present. */
    boolean configPresent(TargetSpec target) {
        if (target == null) {
            return false;
        }
        String check = "[ -s " + target.configPath() + " ]";
        if (!target.legacyConfigPath().isEmpty()) {
            check += " || [ -s " + target.legacyConfigPath() + " ]";
        }
        String out = RootShell.run("if " + check + "; then echo 1; else echo 0; fi", 8)
                .trim();
        return "1".equals(out);
    }

    /** Reads the module's self-check for one target, if that target has run since the publish. */
    String readCheck(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return "";
        }
        return RootShell.run(
                "F=/proc/1/root/data/data/" + target.packageName() + "/files/hookcheck.txt; "
                        + "[ -s \"$F\" ] || F=/data/data/" + target.packageName()
                        + "/files/hookcheck.txt; cat \"$F\" 2>/dev/null",
                8192);
    }

    /** True when the target process is alive; read-only status, never a launch. */
    boolean targetRunning(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return false;
        }
        String pid = RootShell.run("pidof " + target.packageName(), 64).trim();
        return pid.matches("\\d+");
    }

    /**
     * Version read through the root shell. Used when the app's package-visibility view omits a
     * package that is demonstrably installed; never used to infer presence without a path check.
     */
    String installedVersion(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return "";
        }
        String out = RootShell.run(
                "dumpsys package " + target.packageName()
                        + " 2>/dev/null | grep -m1 'versionName='"
                        + " | tr -d ' ' | cut -d= -f2",
                64).trim();
        return out.matches("[A-Za-z0-9._+-]+") ? out : "";
    }
}
