package com.walter.claudewilson;

import android.content.Context;
import android.util.Base64;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

/** Device-side control plane for the Vector module: config file and target self-check. */
final class RootActions {
    private static final String CONFIG_DIR = "/data/local/tmp/claudewilson";
    private static final Object IPV6_SYNC_LOCK = new Object();

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
        openSimManager(null);
    }

    static void openSimManager(Context context) {
        if (RootShell.shellReady()) {
            String out = RootShell.run(
                    "am start -a android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS 2>&1");
            if (out == null || out.contains("Error") || out.contains("does not exist")
                    || out.contains("Exception")) {
                RootShell.run("am start -a android.settings.NETWORK_OPERATOR_SETTINGS");
            }
            return;
        }
        if (context == null) return;
        for (String action : new String[]{
                "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS",
                android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS}) {
            try {
                android.content.Intent intent = new android.content.Intent(action);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
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
        String enriched = enrichDynamicEnvironment(json);
        String out = writeConfig(target.configPath(), enriched);
        cleanupLegacyTargetFiles(target);
        return out;
    }

    /**
     * Dynamically injects the device's live virtual/tunnel network interfaces and default/CustomTabs
     * browser packages into the published payload so hooks never depend on hardcoded names alone.
     */
    private String enrichDynamicEnvironment(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            java.util.List<String> tunnels = discoverTunnelInterfaces();
            org.json.JSONArray ifArr = new org.json.JSONArray();
            for (String iface : tunnels) {
                ifArr.put(iface);
            }
            obj.put("hiddenIfaces", ifArr);

            java.util.List<String> browsers = discoverCompanionBrowsers(context);
            org.json.JSONArray brArr = new org.json.JSONArray();
            for (String pkg : browsers) {
                brArr.put(pkg);
            }
            obj.put("companionBrowsers", brArr);
            return obj.toString();
        } catch (Throwable ignored) {
            return json;
        }
    }

    /** Queries the running kernel for active TUN/TAP and non-cellular POINTOPOINT interfaces. */
    java.util.List<String> discoverTunnelInterfaces() {
        if (!RootShell.shellReady()) {
            return java.util.Collections.emptyList();
        }
        String out = RootShell.run(
                "ip -o link show 2>/dev/null; echo '@@TUN@@'; ls /sys/class/net/*/tun_flags 2>/dev/null || true",
                16384, 4000L);
        if (out == null || out.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int sep = out.indexOf("@@TUN@@");
        String ipLink = sep >= 0 ? out.substring(0, sep) : out;
        String tunFlags = sep >= 0 ? out.substring(sep + 7) : "";
        return com.walter.claudewilson.model.NetworkRules.parseTunnelInterfaces(ipLink, tunFlags);
    }

    /** Dynamically resolves this device's default HTTPS browser package (never hardcoded). */
    static String resolveDefaultBrowserPackage(Context ctx) {
        if (ctx == null) {
            return "";
        }
        String selfPkg = ctx.getApplicationContext().getPackageName();
        try {
            android.content.pm.PackageManager pm = ctx.getApplicationContext().getPackageManager();
            android.content.Intent view = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"));
            view.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            android.content.pm.ResolveInfo ri = pm.resolveActivity(
                    view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                String pkg = ri.activityInfo.packageName.trim();
                if (isValidCompanionBrowserPkg(pkg, selfPkg)) {
                    return pkg;
                }
            }
        } catch (Throwable ignored) {
        }
        if (RootShell.shellReady()) {
            String out = RootShell.run(
                    "cmd package resolve-activity -a android.intent.action.VIEW -d https://example.com 2>/dev/null"
                            + " | grep -m1 'packageName=' | sed 's/.*packageName=//'",
                    256, 3000L);
            if (out != null) {
                String pkg = out.trim();
                if (isValidCompanionBrowserPkg(pkg, selfPkg)) {
                    return pkg;
                }
            }
        }
        return "";
    }

    /** Collects the device's default browser plus installed Custom Tabs providers dynamically. */
    static java.util.List<String> discoverCompanionBrowsers(Context ctx) {
        java.util.LinkedHashSet<String> pkgs = new java.util.LinkedHashSet<String>();
        if (ctx == null) {
            return java.util.Collections.emptyList();
        }
        String selfPkg = ctx.getApplicationContext().getPackageName();
        String defPkg = resolveDefaultBrowserPackage(ctx);
        if (!defPkg.isEmpty()) {
            pkgs.add(defPkg);
        }
        try {
            android.content.pm.PackageManager pm = ctx.getApplicationContext().getPackageManager();
            android.content.Intent ct = new android.content.Intent(
                    "android.support.customtabs.action.CustomTabsService");
            java.util.List<android.content.pm.ResolveInfo> services = pm.queryIntentServices(ct, 0);
            if (services != null) {
                for (android.content.pm.ResolveInfo ri : services) {
                    if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                        String pkg = ri.serviceInfo.packageName.trim();
                        if (isValidCompanionBrowserPkg(pkg, selfPkg)) {
                            pkgs.add(pkg);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new java.util.ArrayList<String>(pkgs);
    }

    private static boolean isValidCompanionBrowserPkg(String pkg, String selfPkg) {
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg) || pkg.equals(selfPkg)) {
            return false;
        }
        if (!ShellQuote.packageName(pkg)) {
            return false;
        }
        return com.walter.claudewilson.model.TargetRegistry.byPackage(pkg) == null
                && !"com.google.android.gms".equals(pkg);
    }

    /**
     * Removes any leftover diagnostic files written by older builds inside the target app's
     * private or external storage so the target directory stays completely free of residue.
     */
    void cleanupLegacyTargetFiles(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return;
        }
        String pkg = target.packageName();
        String script = "rm -rf /data/data/" + pkg + "/cache/cw-telemetry "
                + "/data/data/" + pkg + "/files/hookcheck.txt "
                + "/data/data/" + pkg + "/files/hookcheck.*.txt "
                + "/storage/emulated/*/Android/data/" + pkg + "/files/cw-hookcheck.txt "
                + "/storage/emulated/*/Android/data/" + pkg + "/files/cw-hookcheck.*.txt "
                + "2>/dev/null || true";
        RootShell.run(script, 64);
    }

    private String writeConfig(String path, String json) {
        String encoded = Base64.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        // base64 is available in Android's toybox on every supported release. The JSON itself is
        // never interpolated into the shell; only the base64 alphabet is. CONFIG_DIR is kept 0777
        // and the target file is unlinked before redirect so root and Shizuku (uid 2000) can both
        // update or clear the config across privilege-mode switches without ownership deadlocks.
        String dirPerm = RootShell.rootReady()
                ? "chmod 777 " + CONFIG_DIR
                : "(chmod 777 " + CONFIG_DIR + " 2>/dev/null || chmod 755 " + CONFIG_DIR + " 2>/dev/null || true)";
        String script = "mkdir -p " + CONFIG_DIR
                + " && " + dirPerm
                + " && rm -f " + path
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
        String out = RootShell.runCapture(script + " && sync");
        cleanupLegacyTargetFiles(target);
        return out;
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

    /**
     * Synchronizes the system_server anti-restart lock state for {@code target} under
     * {@code /data/local/tmp/claudewilson/lock/}. Zero files are ever touched inside the target
     * app directory.
     */
    void syncBodyguardLock(TargetSpec target, boolean locked) {
        if (target == null || !ShellQuote.packageName(target.packageName())
                || !RootShell.rootReady()) {
            return;
        }
        String dir = CONFIG_DIR + "/lock";
        String lockFile = dir + "/" + target.id() + ".lock";
        String unlockFile = dir + "/" + target.id() + ".unlock";
        String now = String.valueOf(System.currentTimeMillis());
        String script;
        if (locked) {
            script = "mkdir -p " + dir
                    + " && chmod 755 " + dir
                    + " && rm -f " + unlockFile
                    + " && printf '%s' '" + now + "' > " + lockFile
                    + " && chmod 644 " + lockFile
                    + " && am force-stop " + target.packageName()
                    + " 2>/dev/null || true";
        } else {
            script = "mkdir -p " + dir
                    + " && chmod 755 " + dir
                    + " && rm -f " + lockFile
                    + " && printf '%s' '" + now + "' > " + unlockFile
                    + " && chmod 644 " + unlockFile
                    + " 2>/dev/null || true";
        }
        RootShell.run(script, 64);
    }

    /**
     * Persists the standalone Bodyguard mode sentinel ({@code /data/local/tmp/claudewilson/guard.mode})
     * decoupled from per-target persona payloads so {@code BLOCK_KILL} fails closed even when no
     * region persona is active or a payload was cleared/corrupted.
     */
    void syncBodyguardGuardMode(String mode) {
        if (!RootShell.shellReady()) {
            return;
        }
        String norm = com.walter.claudewilson.model.BodyguardPolicy.normalizeMode(mode);
        String guardFile = CONFIG_DIR + "/guard.mode";
        String dirPerm = RootShell.rootReady()
                ? "chmod 777 " + CONFIG_DIR
                : "(chmod 777 " + CONFIG_DIR + " 2>/dev/null || chmod 755 " + CONFIG_DIR + " 2>/dev/null || true)";
        String script = "mkdir -p " + CONFIG_DIR
                + " && " + dirPerm
                + " && rm -f " + guardFile
                + " && printf '%s' '" + norm + "' > " + guardFile
                + " && chmod 644 " + guardFile
                + " && sync";
        RootShell.run(script, 64);
    }

    /**
     * Enforces device-wide kernel IPv6 suppression (all non-loopback interfaces + persistent
     * netlink watchdog across network/airplane transitions) when {@code spoofEnabled} is false,
     * or stops the watchdog and restores kernel IPv6 when explicitly turned on.
     */
    void syncIpv6Enforcement(boolean spoofEnabled) {
        if (!RootShell.rootReady()) {
            return;
        }
        synchronized (IPV6_SYNC_LOCK) {
            String script = com.walter.claudewilson.model.Ipv6Policy.shellSyncCommand(spoofEnabled);
            RootShell.run(script, 256, 8000L);
        }
    }

    /** True when the target process is alive; read-only status, never a launch. */
    boolean targetRunning(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return false;
        }
        String pid = RootShell.run("pidof " + target.packageName(), 64).trim();
        return pid.matches("\\d+(\\s+\\d+)*");
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

    /** Version code read through the privileged shell when package visibility omits the target. */
    long installedVersionCode(TargetSpec target) {
        if (target == null || !ShellQuote.packageName(target.packageName())) {
            return 0L;
        }
        String out = RootShell.run(
                "dumpsys package " + target.packageName()
                        + " 2>/dev/null | grep -m1 'versionCode='"
                        + " | sed -n 's/.*versionCode=\\([0-9][0-9]*\\).*/\\1/p'",
                32).trim();
        try {
            return out.matches("\\d+") ? Long.parseLong(out) : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
