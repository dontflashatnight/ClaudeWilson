package com.walter.claudewilson;

import android.content.pm.PackageManager;

import com.walter.claudewilson.model.Command;
import com.walter.claudewilson.model.Outcome;
import com.walter.claudewilson.model.Output;
import com.walter.claudewilson.model.ShellQuote;

/**
 * Privileged command channel: root when available, otherwise a Shizuku shell.
 *
 * <p>Every command runs through one transport chosen at probe time. Root grants the full
 * surface (properties, SIM props, carrier config); Shizuku grants shell-uid operations such as
 * timezone, per-app locale, package queries and network probes. A Shizuku server that was
 * started as root is a third shape: the transport stays Shizuku, but every command executes as
 * uid 0 and {@link #rootReady()} is true. The class deliberately keeps a small, synchronous
 * shell surface: region writes and package actions are short, and the UI already runs them off
 * the main thread.
 */
public final class RootShell {

    /** No root binary / never probed. */
    public static final int ST_NO_BINDER = 0;
    /** {@code su} exists but the grant was denied. */
    public static final int ST_NO_PERM = 1;
    /** {@code su} returned uid 0. */
    public static final int ST_READY = 2;

    public static final int ID_UNKNOWN = -1;
    public static final int ID_NONE = 0;
    /** Shizuku's shell uid (2000), not root. */
    public static final int ID_SHELL = 1;
    public static final int ID_ROOT = 2;

    private static final String BRIDGE = "com.walter.claudewilson.Bridge";
    private static String self = "com.walter.claudewilson";

    private static final int TRANSPORT_NONE = 0;
    private static final int TRANSPORT_ROOT = 1;
    private static final int TRANSPORT_SHIZUKU = 2;

    private static volatile int state = ST_NO_BINDER;
    private static volatile int transport = TRANSPORT_NONE;
    private static volatile int shizukuIdentity = ID_UNKNOWN;
    private static volatile boolean rootCached;
    private static volatile boolean probing;
    private static volatile boolean lastProbeFailed;
    private static volatile String lastError = "";
    private static volatile int lastSuRc = Integer.MIN_VALUE;

    private static volatile java.util.function.IntConsumer permissionListener;
    private static volatile Runnable changeListener;
    private static volatile Subscription activeSubscription;

    private RootShell() {
    }

    static void bindSelf(String packageName) {
        if (ShellQuote.packageName(packageName)) {
            self = packageName;
        }
    }

    /**
     * Package of the default browser, or null. It has to carry the same locale as the target
     * applications because {@code navigator.language} and the timezone a web page sees come
     * from it.
     */
    static String browserPackage(android.content.Context ctx) {
        try {
            android.content.Intent view = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://example.com"));
            android.content.pm.ResolveInfo ri =
                    ctx.getPackageManager().resolveActivity(view, 0);
            return ri != null && ri.activityInfo != null
                    ? ri.activityInfo.packageName : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int state() {
        return state;
    }

    /** Probe {@code su} on a worker; safe to call from the UI thread. */
    public static void requestPerm(int code) {
        if (ShizukuShell.state() == ShizukuShell.ST_NO_PERM) {
            ShizukuShell.requestPermission(code);
        }
        probeAsync();
    }

    /** Probe synchronously; only call from a worker thread. */
    public static boolean probeSync() {
        if (probing) {
            return rootCached;
        }
        probing = true;
        try {
            String out = RootProcess.run("id -u", 64, 30000L).trim();
            String first = out.isEmpty() ? "" : out.split("\\s+")[0];
            boolean root = "0".equals(first);
            boolean denied = out.contains("Permission denied")
                    || out.contains("permission denied")
                    || out.contains("not allowed");
            if (root) {
                transport = TRANSPORT_ROOT;
                shizukuIdentity = ID_UNKNOWN;
                lastProbeFailed = false;
                lastError = "";
                state = ST_READY;
                rootCached = true;
                lastSuRc = 0;
            } else {
                int shizuku = ShizukuShell.state();
                transport = TRANSPORT_SHIZUKU;
                rootCached = false;
                lastSuRc = 1;
                if (shizuku == ShizukuShell.ST_READY) {
                    // Shizuku can be started as root. That channel runs every command as uid 0
                    // even though this app has no su binary, so root-only writes must stay on.
                    shizukuIdentity = shizukuUid();
                    state = ST_READY;
                    lastProbeFailed = false;
                    lastError = "";
                } else {
                    state = shizuku == ShizukuShell.ST_NO_PERM ? ST_NO_PERM : ST_NO_BINDER;
                    lastProbeFailed = true;
                    lastError = out;
                }
            }
            if (!root && state != ST_READY) {
                lastError = denied ? out : "no root or Shizuku";
            }
            com.walter.claudewilson.platform.Diag.info("root",
                    "probe state=" + state + " transport=" + transport
                            + " out=" + out + " err=" + lastError);
            notifyPermission(state == ST_READY);
            notifyChange();
            return state == ST_READY;
        } catch (Throwable t) {
            lastError = t.toString();
            transport = TRANSPORT_NONE;
            state = ST_NO_BINDER;
            return false;
        } finally {
            probing = false;
        }
    }

    public static void probeAsync() {
        if (probing) {
            return;
        }
        Thread worker = new Thread(RootShell::probeSync, "root-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void notifyPermission(boolean granted) {
        java.util.function.IntConsumer listener = permissionListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(granted ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED);
        } catch (Throwable ignored) {
            // A UI listener must not take down the probe thread.
        }
    }

    private static void notifyChange() {
        Runnable listener = changeListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Throwable ignored) {
            // A UI listener must not take down the probe thread.
        }
    }

    public static int identity() {
        if (state() != ST_READY) {
            return ID_NONE;
        }
        if (transport == TRANSPORT_ROOT) {
            return ID_ROOT;
        }
        int cached = shizukuIdentity;
        if (cached != ID_UNKNOWN) {
            return cached;
        }
        shizukuIdentity = shizukuUid();
        return shizukuIdentity;
    }

    /** Shizuku's remote uid: 0 for a root-started server, 2000 for the ordinary shell. */
    private static int shizukuUid() {
        try {
            String out = ShizukuShell.run("id -u", 64, 5000L).trim();
            String uid = out.isEmpty() ? "" : out.split("\\s+")[0];
            return "0".equals(uid) ? ID_ROOT : ID_SHELL;
        } catch (Throwable t) {
            return ID_SHELL;
        }
    }

    /** Forget the cached identity; the next probe re-checks {@code su}. */
    public static void refreshIdentity() {
        rootCached = false;
        transport = TRANSPORT_NONE;
        shizukuIdentity = ID_UNKNOWN;
        state = ST_NO_BINDER;
        lastProbeFailed = false;
        probeAsync();
    }

    /** Any privileged channel (root or Shizuku shell) is usable. */
    static boolean shellReady() {
        return state() == ST_READY;
    }

    public static String identityName() {
        int id = identity();
        return id == ID_ROOT ? "root" : id == ID_SHELL ? "shell" : "none";
    }

    /** Shell-side account dump filtered to the marker and Google account lines. */
    public static String googleAccountsDump() {
        return run("dumpsys account | grep -E 'Accounts:|, type=com.google}'", 0);
    }

    /**
     * True when the live channel runs as uid 0.
     *
     * <p>Two shapes qualify: {@code su} itself, and a Shizuku server started as root. A shell-uid
     * Shizuku does not; property writes and {@code /data} reads would fail there. The transport
     * choice stays in {@link #run}; this predicate only answers "may root-only work run now".
     */
    static boolean rootReady() {
        if (state != ST_READY) {
            return false;
        }
        if (transport == TRANSPORT_ROOT) {
            return true;
        }
        return transport == TRANSPORT_SHIZUKU && shizukuIdentity == ID_ROOT;
    }

    static void grantPermissions(String pkg, String[] permissions) {
        if (!ShellQuote.packageName(pkg)) return;
        for (String permission : permissions) run("pm grant " + pkg + " " + permission);
    }

    static String applyPackageAction(String pkg, int action) {
        if (!ShellQuote.packageName(pkg)) {
            return "refused: invalid package name";
        }
        switch (action) {
            case 0: return run("pm suspend " + pkg);
            case 1: return run("pm unsuspend " + pkg);
            case 2: return run("pm uninstall --user 0 " + pkg);
            case 3: return run("pm clear " + pkg);
            default: throw new IllegalArgumentException("package action: " + action);
        }
    }

    static Subscription observe(java.util.function.IntConsumer permission, Runnable binder) {
        Subscription subscription = new Subscription(permission, binder);
        activeSubscription = subscription;
        probeAsync();
        return subscription;
    }

    static final class Subscription implements AutoCloseable {
        private final java.util.function.IntConsumer permission;
        private final Runnable binder;
        private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermission;
        private final rikka.shizuku.Shizuku.OnBinderReceivedListener shizukuReceived;
        private final rikka.shizuku.Shizuku.OnBinderDeadListener shizukuDead;

        Subscription(java.util.function.IntConsumer onPermission, Runnable onBinder) {
            permission = onPermission;
            binder = onBinder;
            permissionListener = onPermission;
            changeListener = onBinder;
            shizukuPermission = (code, grantResult) -> {
                refreshIdentity();
                onPermission.accept(grantResult == PackageManager.PERMISSION_GRANTED
                        ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED);
            };
            shizukuReceived = () -> {
                refreshIdentity();
                onBinder.run();
            };
            shizukuDead = () -> {
                refreshIdentity();
                onBinder.run();
            };
            try {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermission);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
            try {
                rikka.shizuku.Shizuku.addBinderReceivedListener(shizukuReceived);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
            try {
                rikka.shizuku.Shizuku.addBinderDeadListener(shizukuDead);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
        }

        @Override public void close() {
            if (activeSubscription == this) {
                activeSubscription = null;
            }
            if (permissionListener == permission) permissionListener = null;
            if (changeListener == binder) changeListener = null;
            try {
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermission);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
            try {
                rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuReceived);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
            try {
                rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuDead);
            } catch (Throwable ignored) {
                // Shizuku not installed/running.
            }
        }
    }

    /**
     * Apply a region: manual timezone (auto detection off), a per-app locale, a carrier-config
     * override per active subscription, and the build/operator properties when root is present.
     *
     * <p>Root keeps the target's real locale configuration untouched and lets the in-process hook
     * present {@code locale}; Shizuku has no hook, so it writes the target and browser app locales.
     * SIM props and build props stay root-only.
     *
     * <p>Timezone, locale and ISO are validated as shell-safe tokens and interpolated bare; the
     * carrier name is quoted because it legitimately contains spaces and non-ASCII characters.
     */
    /** Manual timezone write through whichever privileged channel is live (root or Shizuku). */
    public static String setTimeZone(String zone) {
        if (!shellReady()) {
            return "exc:no-channel";
        }
        if (!ShellQuote.safeToken(zone)) {
            return "exc:invalid-zone";
        }
        return run("cmd time_zone_detector set_auto_detection_enabled false; "
                + "cmd alarm set-timezone " + zone, 512, 15000L);
    }

    public static Outcome applyRegion(String zone, String locale, String iso,
                                      String carrier, int sub, String op,
                                      String browserPkg, String targetPkg,
                                      String[] device, boolean buildChanged) {
        if (!ShellQuote.safeToken(zone) || !ShellQuote.safeToken(locale)
                || !ShellQuote.safeToken(iso)) {
            return Outcome.of("refused: unsafe region token");
        }
        boolean opSafe = op != null && op.matches("\\d{5,6}");
        boolean hasBrowser = browserPkg != null && ShellQuote.packageName(browserPkg);
        boolean root = rootReady();
        Command command = Command.sh()
                .raw("APK=$(pm path " + self + " | cut -d: -f2)")
                .step("auto-detection",
                        "cmd time_zone_detector set_auto_detection_enabled false")
                .step("timezone", "cmd alarm set-timezone " + zone);
        // Per-app locale is written on every channel. The hook still supplies the richer
        // in-process locale, but a partially installed hook must not leave the target on the
        // system language; the app locale is the fallback that keeps the UI language aligned.
        if (ShellQuote.packageName(targetPkg)) {
            command.step("locale-target",
                    "cmd locale set-app-locales " + targetPkg + " --locales " + locale);
            if (hasBrowser) {
                command.step("locale-browser",
                        "cmd locale set-app-locales " + browserPkg + " --locales " + locale);
            }
        }
        if (root && device != null && buildChanged) {
            command.step("build-props", "sh -c " + ShellQuote.quote(
                    SystemBuild.applyScript(device, op, iso, carrier)));
        } else if (root && opSafe) {
            command.step("operator-props", "sh -c " + ShellQuote.quote(
                    SystemBuild.operatorScript(op, iso, carrier)));
        } else {
            command.step("build-props", "echo SKIP:build-props shell-channel");
        }
        if (sub >= 0) {
            command.step("carrier", carrierStep(sub, iso, carrier));
            if (carrier != null && !carrier.isEmpty()) {
                command.step("carrier-name", subnameStep(sub, carrier,
                        CarrierCommands.nameSourceUserInput()));
            }
            if (root && opSafe) {
                command.step("sim-props", "sh -c " + ShellQuote.quote(
                        SystemBuild.telephonyScript(op, iso, carrier)));
                command.step("simop", simopApiAbsent
                        ? "echo SKIP:simop api-absent"
                        : "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                                + " simop " + sub + " " + op + " "
                                + ShellQuote.quote(carrier));
            }
        }
        String output = run(command.done("APPLY:DONE").build());
        observeCarrierDenial(output);
        if (output.contains("SKIP:simop api-absent")) {
            simopApiAbsent = true;
        }
        return Outcome.of(output, command.stepNames());
    }

    /**
     * Undo: drop the carrier override, restore the original timezone (auto detection alone will
     * not recompute a zone until a network signal arrives), and clear the per-app locale override
     * written on a Shizuku apply.
     */
    public static Outcome revert(int sub, String origTz, int autoFlag,
                                 String[] origSim, String browserPkg, String targetPkg) {
        boolean root = rootReady();
        Command command = Command.sh()
                .raw("APK=$(pm path " + self + " | cut -d: -f2)");
        // The apply wrote per-app locale on every channel; the revert must clear it on every
        // channel too, not only on the shell fallback path.
        if (ShellQuote.packageName(targetPkg)) {
            command.step("locale-target-reset",
                    "(pm path " + targetPkg + " >/dev/null 2>&1 && cmd locale set-app-locales "
                            + targetPkg + ") || echo SKIP:locale-target-reset");
            if (browserPkg != null && ShellQuote.packageName(browserPkg)) {
                command.step("locale-browser-reset",
                        "(pm path " + browserPkg + " >/dev/null 2>&1 && cmd locale set-app-locales "
                                + browserPkg + ") || echo SKIP:locale-browser-reset");
            }
        }
        if (sub >= 0) {
            command.step("carrier-clear", carrierClearStep(sub));
            String restoreName = origSim != null && origSim.length > 3
                    && origSim[3] != null && !origSim[3].isEmpty()
                    ? origSim[3]
                    : origSim != null && origSim.length > 2 ? origSim[2] : "";
            if (!restoreName.isEmpty()) {
                int source = CarrierCommands.nameSourceUserInput();
                if (origSim.length > 4 && origSim[4] != null
                        && origSim[4].matches("\\d+")) {
                    source = Integer.parseInt(origSim[4]);
                }
                command.step("carrier-name-restore", subnameStep(sub, restoreName, source));
            }
            if (root) {
                command.step("simop-clear", simopApiAbsent
                        ? "echo SKIP:simop-clear api-absent"
                        : "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                                + " simop " + sub + " - -");
                if (origSim != null && origSim[0] != null && !origSim[0].isEmpty()) {
                    command.step("sim-props-restore",
                            "setprop gsm.sim.operator.numeric "
                                    + ShellQuote.quote(origSim[0])
                                    + " && setprop gsm.sim.operator.iso-country "
                                    + ShellQuote.quote(origSim[1])
                                    + " && setprop gsm.sim.operator.alpha "
                                    + ShellQuote.quote(origSim[2]));
                } else {
                    command.step("sim-props-clear",
                            "if command -v resetprop >/dev/null 2>&1; then"
                                    + " resetprop --delete gsm.sim.operator.numeric;"
                                    + " resetprop --delete gsm.sim.operator.iso-country;"
                                    + " resetprop --delete gsm.sim.operator.alpha;"
                                    + " else echo SKIP:sim-props-clear; fi");
                }
            }
        }
        if (ShellQuote.safeToken(origTz)) {
            command.step("timezone-restore", "cmd alarm set-timezone " + origTz);
        }
        command.step("build-restore", root
                ? "sh -c " + ShellQuote.quote(SystemBuild.restoreScript())
                : "echo SKIP:build-restore shell-channel");
        command.step("auto-detection-restore",
                "cmd time_zone_detector set_auto_detection_enabled " + (autoFlag == 1));
        String output = run(command.done("REVERT:DONE").build());
        observeCarrierDenial(output);
        if (output.contains("SKIP:simop") && output.contains("api-absent")) {
            simopApiAbsent = true;
        }
        return Outcome.of(output, command.stepNames());
    }

    private static volatile boolean simopApiAbsent;

    /** Carrier-config override as a root step. */
    private static String carrierStep(int sub, String iso, String carrier) {
        return wrappedCarrierCall("carrier",
                "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                        + " cc " + sub + " " + iso + " " + ShellQuote.quote(carrier));
    }

    /** Subscription display name, which is what Settings' SIM manager renders. */
    private static String subnameStep(int sub, String name, int source) {
        return wrappedCarrierCall("carrier-name",
                "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                        + " subname " + sub + " " + ShellQuote.quote(name) + " " + source);
    }

    /** Carrier-config reassert for the reconciler; APK is not predefined in a standalone script. */
    static String simReassertScript(int sub, String iso, String carrier) {
        if (sub < 0) {
            return "echo SKIP:carrier no-active-sub";
        }
        return "APK=$(pm path " + self + " | cut -d: -f2); "
                + carrierStep(sub, iso, carrier);
    }

    private static String carrierClearStep(int sub) {
        return wrappedCarrierCall("carrier-clear",
                "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                        + " ccclear " + sub);
    }

    private static String wrappedCarrierCall(String name, String call) {
        return "out=$(" + call + " 2>&1); rc=$?; "
                + "if [ $rc -ne 0 ]; then "
                + "case \"$out\" in "
                + "*\"cannot be invoked by shell\"*) "
                + "echo SKIP:" + name + " permission-denied; rc=0;; "
                + "*) echo \"$out\";; "
                + "esac; "
                + "else echo \"$out\"; fi; "
                + "[ $rc -eq 0 ]";
    }

    private static void observeCarrierDenial(String output) {
        // Root does not need the shell fallback; retained for log compatibility.
    }

    /** Root shell runner; returns combined output, or {@code exc:}/{@code timeout:} on failure. */
    public static String run(String sh) { return run(sh, Output.MAX); }

    public static String runCapture(String script) {
        return run("exec 2>&1; " + script);
    }

    public static String run(String sh, int max) {
        return run(sh, max, 20000L);
    }

    public static String run(String sh, int max, long timeoutMs) {
        String out;
        if (transport == TRANSPORT_SHIZUKU) {
            out = ShizukuShell.run(sh, max, timeoutMs);
        } else if (transport == TRANSPORT_ROOT) {
            out = RootProcess.run(sh, max, timeoutMs);
        } else {
            out = RootProcess.run(sh, max, timeoutMs);
            if (ShellQuote.failed(out) && ShizukuShell.ready()) {
                out = ShizukuShell.run(sh, max, timeoutMs);
            }
        }
        lastSuRc = ShellQuote.suRc(out);
        return out;
    }

    /**
     * Wraps a root-only payload for the live channel.
     *
     * <p>Only the {@code su} transport needs the extra wrapper. A root-started Shizuku already
     * executes the payload as uid 0, and there may be no {@code su} binary on such a device, so
     * wrapping it would turn a working write into "command not found".
     */
    static String wrapRoot(String payload) {
        return transport == TRANSPORT_ROOT ? Command.suWrap(payload) : payload;
    }

    /** Runs a root-only payload through whichever channel currently holds uid 0. */
    static String runRoot(String payload) {
        return run(wrapRoot(payload));
    }

    static String runRoot(String payload, int max, long timeoutMs) {
        return run(wrapRoot(payload), max, timeoutMs);
    }

    /** True when the last root command failed at the transport layer. */
    public static boolean rootRefused() {
        return state() != ST_READY || lastProbeFailed;
    }

    /** True when the output reports any failed step. */
    public static boolean failed(String output) {
        return ShellQuote.failed(output);
    }

    /** A hint when the failure looks like a refused {@code su} rather than a failed step. */
    public static String suHint(String output) {
        if (output == null) return "";
        if (output.contains("su:") || output.contains("Permission denied")
                || output.contains("not allowed")) {
            if (transport == TRANSPORT_SHIZUKU) {
                return Lang.t("（Shizuku 拒绝该操作：确认服务已启动且本应用已授权）",
                        " (Shizuku refused: start the service and grant this app)");
            }
            return Lang.t("（su 被拒：请在超级用户管理里授权本应用）",
                    " (su refused: grant this app in the superuser manager)");
        }
        return "";
    }

    /** Allow only shell-safe tokens: Asia/Tokyo, ja-JP, en-US,zh-SG. */
    public static boolean safe(String v) {
        return ShellQuote.safeToken(v);
    }

    public static String lastError() {
        return lastError;
    }
}
