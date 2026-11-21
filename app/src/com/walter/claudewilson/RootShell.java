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
    private static volatile boolean probeAgain;
    private static volatile boolean lastProbeFailed;
    private static volatile boolean shellCarrierDenied;
    private static volatile String lastError = "";
    private static volatile int lastSuRc = Integer.MIN_VALUE;

    private static volatile java.util.function.IntConsumer permissionListener;
    private static volatile Runnable changeListener;
    private static volatile Subscription activeSubscription;

    private static volatile String selfApk = "";

    private RootShell() {
    }

    static void bindSelf(android.content.Context context) {
        if (context == null) return;
        bindSelf(context.getPackageName());
        try {
            String dir = context.getApplicationInfo().sourceDir;
            if (dir != null && dir.startsWith("/") && dir.endsWith(".apk")
                    && dir.indexOf('\'') < 0 && dir.indexOf('"') < 0 && dir.indexOf(' ') < 0) {
                selfApk = dir;
            }
        } catch (Throwable ignored) {
        }
    }

    static void bindSelf(String packageName) {
        if (ShellQuote.packageName(packageName)) {
            self = packageName;
        }
    }

    private static String apkAssign() {
        String cached = selfApk;
        if (cached != null && !cached.isEmpty()) {
            return "APK=" + ShellQuote.quote(cached)
                    + "; [ -f \"$APK\" ] || APK=$(pm path " + self + " | head -n 1 | cut -d: -f2)";
        }
        return "APK=$(pm path " + self + " | head -n 1 | cut -d: -f2)";
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
        synchronized (RootShell.class) {
            if (probing) {
                probeAgain = true;
                return state == ST_READY;
            }
            probing = true;
            probeAgain = false;
        }
        try {
            while (true) {
                boolean shizukuReadyNow = ShizukuShell.ready();
                long suTimeout = shizukuReadyNow ? 4000L : 30000L;
                String out = RootProcess.run("id -u", 64, suTimeout).trim();
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
                        shizukuIdentity = ID_UNKNOWN;
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
                synchronized (RootShell.class) {
                    if (probeAgain) {
                        probeAgain = false;
                        continue;
                    }
                    probing = false;
                    break;
                }
            }
            notifyPermission(state == ST_READY);
            notifyChange();
            return state == ST_READY;
        } catch (Throwable t) {
            synchronized (RootShell.class) {
                probing = false;
                probeAgain = false;
            }
            lastError = t.toString();
            transport = TRANSPORT_NONE;
            state = ST_NO_BINDER;
            return false;
        }
    }

    public static void probeAsync() {
        synchronized (RootShell.class) {
            if (probing) {
                probeAgain = true;
                return;
            }
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

    /** Forget the cached identity; the next probe re-checks {@code su} and Shizuku. */
    public static void refreshIdentity() {
        rootCached = false;
        shizukuIdentity = ID_UNKNOWN;
        lastProbeFailed = false;
        if (!ShizukuShell.ready() && transport == TRANSPORT_SHIZUKU) {
            transport = TRANSPORT_NONE;
            state = ShizukuShell.state() == ShizukuShell.ST_NO_PERM ? ST_NO_PERM : ST_NO_BINDER;
        }
        probeAsync();
    }

    /** True when the ROM explicitly rejected shell-uid CarrierConfig overrides. */
    static boolean shellCarrierDenied() {
        return !rootReady() && shellCarrierDenied;
    }

    /** True when the live channel can override the SIM country ISO. */
    static boolean simCountryWritable() {
        return rootReady() || (shellReady() && !preservesMobileData() && !shellCarrierDenied);
    }

    /** True when the live channel can override the SIM/subscription carrier display name. */
    static boolean simCarrierWritable() {
        return rootReady() || (shellReady() && !shellCarrierDenied);
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
                // Re-probe and let probeSync deliver onPermission/onBinder once state and
                // shizukuIdentity are settled; firing early raced ST_NO_BINDER.
                refreshIdentity();
            };
            shizukuReceived = () -> {
                refreshIdentity();
            };
            shizukuDead = () -> {
                if (transport == TRANSPORT_SHIZUKU) {
                    transport = TRANSPORT_NONE;
                    shizukuIdentity = ID_UNKNOWN;
                    state = ST_NO_BINDER;
                }
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
        return run(autoTimeZoneCmd(false) + "; "
                + "cmd alarm set-timezone " + zone, 512, 15000L);
    }

    public static Outcome applyRegion(String zone, String locale, String iso,
                                      String carrier, int sub, String op,
                                      String browserPkg, String targetPkg,
                                      String[] device, boolean buildChanged,
                                      String[] realSim, String userId) {
        return applyRegion(zone, locale, iso, carrier, sub, op, browserPkg, targetPkg,
                device, buildChanged, realSim, userId, false);
    }

    public static Outcome applyRegion(String zone, String locale, String iso,
                                      String carrier, int sub, String op,
                                      String browserPkg, String targetPkg,
                                      String[] device, boolean buildChanged,
                                      String[] realSim, String userId,
                                      boolean preserveSim) {
        if (!ShellQuote.safeToken(zone) || !ShellQuote.safeToken(locale)
                || !ShellQuote.safeToken(iso)) {
            return Outcome.of("refused: unsafe region token");
        }
        String userOpt = userId != null && ShellQuote.numeric(userId) && !"0".equals(userId)
                ? " --user " + userId : "";
        boolean opSafe = op != null && op.matches("\\d{5,6}");
        boolean hasBrowser = browserPkg != null && ShellQuote.packageName(browserPkg);
        boolean root = rootReady();
        boolean parallelBridge = root && sub >= 0 && !preserveSim && !preservesMobileData();
        Command command = Command.sh()
                .raw(apkAssign());
        if (parallelBridge) {
            // Overlap slow cmd binder forks (timezone + per-app locales) with Bridge ART startup.
            // Keep auto-detection and per-app locales best-effort so an optional step never
            // aborts timezone or triggers a false rollback of the whole region apply.
            String userTrim = userOpt.trim();
            String pmUser = userTrim.isEmpty() ? "" : userTrim + " ";
            StringBuilder bg = new StringBuilder();
            bg.append("{ ").append(autoTimeZoneCmd(false)).append(" >/dev/null 2>&1; ")
                    .append("cmd alarm set-timezone ").append(zone).append("; CW_TZ_RC=$?; ");
            if (ShellQuote.packageName(targetPkg)) {
                bg.append("(pm path ").append(pmUser).append(targetPkg)
                        .append(" >/dev/null 2>&1 && cmd locale set-app-locales ")
                        .append(targetPkg).append(userOpt)
                        .append(" --locales ").append(locale)
                        .append(") >/dev/null 2>&1 || true; ");
                if (hasBrowser) {
                    bg.append("(pm path ").append(pmUser).append(browserPkg)
                            .append(" >/dev/null 2>&1 && cmd locale set-app-locales ")
                            .append(browserPkg).append(userOpt)
                            .append(" --locales ").append(locale)
                            .append(") >/dev/null 2>&1 || true; ");
                }
            }
            bg.append("exit $CW_TZ_RC; } >/dev/null 2>&1 & CW_BG_PID=$!");
            command.raw(bg.toString());
        } else {
            command.step("auto-detection", autoTimeZoneCmd(false))
                    .step("timezone", "cmd alarm set-timezone " + zone);
            if (ShellQuote.packageName(targetPkg)) {
                command.step("locale-target", wrappedLocaleCall("locale-target",
                        "cmd locale set-app-locales " + targetPkg + userOpt + " --locales " + locale,
                        root));
                if (hasBrowser) {
                    command.step("locale-browser", wrappedLocaleCall("locale-browser",
                            "cmd locale set-app-locales " + browserPkg + userOpt
                                    + " --locales " + locale,
                            root));
                }
            }
        }
        if (root && device != null && buildChanged) {
            command.step("build-props", "sh -c " + ShellQuote.quote(
                    SystemBuild.applyScript(device, op, iso, carrier)));
        } else if (root && opSafe && !parallelBridge) {
            command.step("operator-props", "sh -c " + ShellQuote.quote(
                    SystemBuild.operatorScript(op, iso, carrier)));
        } else if (!parallelBridge) {
            command.step("build-props", "echo SKIP:build-props shell-channel");
        }
        if (sub >= 0) {
            if (preserveSim) {
                command.step("sim-preserve", "echo SKIP:sim-preserve non-restricted");
            } else if (preservesMobileData()) {
                command.step("sim-real", root
                        ? simRealStep(realSim) : "echo SKIP:sim-real no-root");
                command.step("carrier-clear", carrierClearStep(sub));
                command.step("simop-clear", !root
                        ? "echo SKIP:simop-clear no-root"
                        : simopApiAbsent
                                ? "echo SKIP:simop-clear api-absent"
                                : "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                                        + " simop " + sub + " - -");
                if (carrier != null && !carrier.isEmpty()) {
                    command.step("carrier-name", subnameStep(sub, carrier, -1));
                    if (root) {
                        command.step("sim-alpha", simAlphaStep(carrier));
                    }
                }
                command.step("sim-data-compat", "echo SKIP:sim-data-compat oem-apn");
            } else if (root) {
                StringBuilder batch = new StringBuilder();
                batch.append("app_process -Djava.class.path=$APK /system/bin ").append(BRIDGE)
                        .append(" batch cc ").append(sub).append(' ').append(iso).append(' ')
                        .append(ShellQuote.quote(carrier));
                if (carrier != null && !carrier.isEmpty()) {
                    batch.append(" -- subname ").append(sub).append(' ')
                            .append(ShellQuote.quote(carrier)).append(" -1");
                }
                if (opSafe && !simopApiAbsent) {
                    batch.append(" -- simop ").append(sub).append(' ').append(op).append(' ')
                            .append(ShellQuote.quote(carrier));
                }
                command.step("carrier", batch.toString());
                if (opSafe) {
                    command.step("sim-props", "sh -c " + ShellQuote.quote(
                            SystemBuild.telephonyScript(op, iso, carrier)));
                }
            } else {
                command.step("carrier", carrierStep(sub, iso, carrier));
                if (carrier != null && !carrier.isEmpty()) {
                    command.step("carrier-name", subnameStep(sub, carrier, -1));
                }
            }
        }
        if (parallelBridge) {
            command.step("tz-locale", "wait $CW_BG_PID");
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
                                 String[] origSim, String browserPkg, String targetPkg,
                                 String userId) {
        boolean root = rootReady();
        String userOpt = userId != null && ShellQuote.numeric(userId) && !"0".equals(userId)
                ? " --user " + userId : "";
        Command command = Command.sh()
                .raw(apkAssign());
        // The apply wrote per-app locale on every channel; the revert must clear it on every
        // channel too, not only on the shell fallback path.
        if (ShellQuote.packageName(targetPkg)) {
            command.step("locale-target-reset",
                    "(pm path " + userOpt.trim() + (userOpt.isEmpty() ? "" : " ") + targetPkg
                            + " >/dev/null 2>&1 && cmd locale set-app-locales "
                            + targetPkg + userOpt
                            + " --locales empty) || echo SKIP:locale-target-reset");
            if (browserPkg != null && ShellQuote.packageName(browserPkg)) {
                command.step("locale-browser-reset",
                        "(pm path " + userOpt.trim() + (userOpt.isEmpty() ? "" : " ") + browserPkg
                                + " >/dev/null 2>&1 && cmd locale set-app-locales "
                                + browserPkg + userOpt
                                + " --locales empty) || echo SKIP:locale-browser-reset");
            }
        }
        if (sub >= 0) {
            boolean keepReal = preservesMobileData();
            if (keepReal) {
                // Restore the card's own SIM operator trio before the carrier-config change
                // below. That change makes telephony reconnect, and the reconnect must resolve
                // the real APN, not one keyed by a leftover persona value.
                command.step("sim-real", root
                        ? simRealStep(origSim) : "echo SKIP:sim-real no-root");
            }
            String restoreName = origSim != null && origSim.length > 3
                    && origSim[3] != null && !origSim[3].isEmpty()
                    ? origSim[3]
                    : origSim != null && origSim.length > 2 ? origSim[2] : "";
            int source = -1;
            if (origSim != null && origSim.length > 4 && origSim[4] != null
                    && origSim[4].matches("\\d+")) {
                source = Integer.parseInt(origSim[4]);
            }
            if (root) {
                StringBuilder batch = new StringBuilder();
                batch.append("app_process -Djava.class.path=$APK /system/bin ").append(BRIDGE)
                        .append(" batch ccclear ").append(sub);
                if (!restoreName.isEmpty()) {
                    batch.append(" -- subname ").append(sub).append(' ')
                            .append(ShellQuote.quote(restoreName)).append(' ').append(source);
                }
                if (!simopApiAbsent) {
                    batch.append(" -- simop ").append(sub).append(" - -");
                }
                command.step("carrier-clear", batch.toString());
            } else {
                command.step("carrier-clear", carrierClearStep(sub));
                if (!restoreName.isEmpty()) {
                    command.step("carrier-name-restore", subnameStep(sub, restoreName, source));
                }
            }
            if (root) {
                if (!keepReal) {
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
        }
        if (ShellQuote.safeToken(origTz)) {
            command.step("timezone-restore", "cmd alarm set-timezone " + origTz);
        }
        command.step("build-restore", root
                ? "sh -c " + ShellQuote.quote(SystemBuild.restoreScript(origSim))
                : "echo SKIP:build-restore shell-channel");
        command.step("auto-detection-restore", autoTimeZoneCmd(autoFlag == 1));
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
        if (preservesMobileData()) {
            return "echo SKIP:sim-data-compat oem-apn";
        }
        String script = "APK=$(pm path " + self + " | cut -d: -f2); "
                + carrierStep(sub, iso, carrier);
        if (carrier != null && !carrier.isEmpty()) {
            script += "; " + subnameStep(sub, carrier, -1);
        }
        return script;
    }

    /** Manual SIM row write from PersonaPanel, batching Bridge Binder calls in one ART process. */
    static String simOverrideScript(int sub, String op, String iso, String carrier) {
        boolean root = rootReady();
        boolean keepReal = preservesMobileData();
        boolean opSafe = op != null && op.matches("\\d{5,6}");
        String car = carrier == null ? "" : carrier;
        Command cmd = Command.sh()
                .raw(apkAssign());
        if (root && !keepReal && opSafe && ShellQuote.safeToken(iso)) {
            cmd.step("sim-props", "sh -c " + ShellQuote.quote(
                    SystemBuild.telephonyScript(op, iso, car)));
        }
        if (sub < 0) {
            cmd.raw("echo SKIP:carrier no-active-sub");
            return cmd.done("SIM:DONE").build();
        }
        if (keepReal) {
            if (!car.isEmpty()) {
                cmd.step("carrier-name", subnameStep(sub, car, -1));
                if (root) {
                    cmd.step("sim-alpha", simAlphaStep(car));
                }
            } else {
                cmd.raw("echo SKIP:sim-data-compat oem-apn");
            }
            return cmd.done("SIM:DONE").build();
        }
        if (root && ShellQuote.safeToken(iso)) {
            StringBuilder batch = new StringBuilder();
            batch.append("app_process -Djava.class.path=$APK /system/bin ").append(BRIDGE)
                    .append(" batch cc ").append(sub).append(' ').append(iso).append(' ')
                    .append(ShellQuote.quote(car));
            if (!car.isEmpty()) {
                batch.append(" -- subname ").append(sub).append(' ')
                        .append(ShellQuote.quote(car)).append(" -1");
            }
            if (opSafe && !simopApiAbsent) {
                batch.append(" -- simop ").append(sub).append(' ').append(op).append(' ')
                        .append(ShellQuote.quote(car));
            }
            cmd.step("carrier", batch.toString());
        } else {
            if (ShellQuote.safeToken(iso)) {
                cmd.step("carrier", carrierStep(sub, iso, car));
            }
            if (!car.isEmpty()) {
                cmd.step("carrier-name", subnameStep(sub, car, -1));
            }
        }
        return cmd.done("SIM:DONE").build();
    }

    /** Restores physical SIM props and CarrierConfig in one Bridge batch (used for keepRealSim). */
    static String restoreRealSimScript(int sub, String[] origSim) {
        boolean root = rootReady();
        Command cmd = Command.sh().raw(apkAssign());
        if (root && origSim != null && origSim.length >= 3
                && origSim[0] != null && origSim[0].matches("\\d{5,6}")) {
            cmd.step("sim-real", simRealStep(origSim));
        }
        if (root) {
            cmd.step("net-real", "sh -c " + ShellQuote.quote(
                    SystemBuild.restoreOperatorScript(origSim)));
        }
        if (sub < 0) {
            return cmd.done("SIM:DONE").build();
        }
        String restoreName = origSim != null && origSim.length > 3
                && origSim[3] != null && !origSim[3].isEmpty()
                ? origSim[3]
                : origSim != null && origSim.length > 2 ? origSim[2] : "";
        int source = -1;
        if (origSim != null && origSim.length > 4 && origSim[4] != null
                && origSim[4].matches("\\d+")) {
            source = Integer.parseInt(origSim[4]);
        }
        if (root) {
            StringBuilder batch = new StringBuilder();
            batch.append("app_process -Djava.class.path=$APK /system/bin ").append(BRIDGE)
                    .append(" batch ccclear ").append(sub);
            if (!restoreName.isEmpty()) {
                batch.append(" -- subname ").append(sub).append(' ')
                        .append(ShellQuote.quote(restoreName)).append(' ').append(source);
            }
            if (!simopApiAbsent) {
                batch.append(" -- simop ").append(sub).append(" - -");
            }
            cmd.step("carrier-clear", batch.toString());
        } else {
            cmd.step("carrier-clear", carrierClearStep(sub));
            if (!restoreName.isEmpty()) {
                cmd.step("carrier-name", subnameStep(sub, restoreName, source));
            }
        }
        return cmd.done("SIM:DONE").build();
    }

    /** Writes timezone and per-app locales in a single shell transaction. */
    static String setLocaleAndTimeZone(String zone, String locale,
                                       String targetPkg, String browserPkg, String userId) {
        if (!shellReady()) {
            return "exc:no-channel";
        }
        if (zone != null && !zone.isEmpty() && !ShellQuote.safeToken(zone)) {
            return "exc:invalid-zone";
        }
        if (locale != null && !locale.isEmpty() && !ShellQuote.safeToken(locale)) {
            return "exc:invalid-locale";
        }
        String userOpt = userId != null && ShellQuote.numeric(userId) && !"0".equals(userId)
                ? " --user " + userId : "";
        String pmUser = userOpt.trim().isEmpty() ? "" : userOpt.trim() + " ";
        StringBuilder sb = new StringBuilder();
        if (zone != null && !zone.isEmpty()) {
            sb.append(autoTimeZoneCmd(false)).append(" >/dev/null 2>&1; ")
                    .append("cmd alarm set-timezone ").append(zone).append("; ");
        }
        if (locale != null && !locale.isEmpty()) {
            if (ShellQuote.packageName(targetPkg)) {
                sb.append("(pm path ").append(pmUser).append(targetPkg)
                        .append(" >/dev/null 2>&1 && cmd locale set-app-locales ")
                        .append(targetPkg).append(userOpt)
                        .append(" --locales ").append(locale)
                        .append(") >/dev/null 2>&1 || true; ");
            }
            if (browserPkg != null && ShellQuote.packageName(browserPkg)) {
                sb.append("(pm path ").append(pmUser).append(browserPkg)
                        .append(" >/dev/null 2>&1 && cmd locale set-app-locales ")
                        .append(browserPkg).append(userOpt)
                        .append(" --locales ").append(locale)
                        .append(") >/dev/null 2>&1 || true; ");
            }
        }
        sb.append("echo OK");
        return run(sb.toString(), 512, 15000L);
    }

    /**
     * OEMs that derive their data APN from the real SIM must not receive a global SIM override.
     *
     * <p>{@code Build.MANUFACTURER} is not usable here: this app rewrites
     * {@code ro.product.manufacturer} itself, so a later process start can see the persona and
     * flip the branch. The per-partition manufacturer properties are not written by the build
     * replay and therefore still name the physical device.
     */
    static boolean preservesMobileData() {
        return "samsung".equalsIgnoreCase(physicalManufacturer());
    }

    private static String physicalManufacturer() {
        String[] keys = {
                "ro.product.system.manufacturer",
                "ro.product.vendor.manufacturer",
                "ro.product.odm.manufacturer",
        };
        for (String key : keys) {
            String value = DeviceSignals.getprop(key);
            if (value != null && !value.isEmpty() && value.indexOf(',') < 0) {
                return value;
            }
        }
        return android.os.Build.MANUFACTURER;
    }

    /** Writes only the SIM display name; MCC/MNC, country and APN inputs stay real. */
    private static String simAlphaStep(String alpha) {
        if (alpha == null || alpha.isEmpty()) {
            return "echo SKIP:sim-alpha empty";
        }
        return "sh -c " + ShellQuote.quote(SystemBuild.alphaScript(alpha));
    }

    /** Restores the physical card's SIM operator trio before a carrier-config reconnect. */
    private static String simRealStep(String[] realSim) {
        if (realSim == null || realSim.length < 3
                || realSim[0] == null || !realSim[0].matches("\\d{5,6}")) {
            return "echo SKIP:sim-real no-baseline";
        }
        return "sh -c " + ShellQuote.quote(SystemBuild.simScript(
                realSim[0], realSim[1] == null ? "" : realSim[1],
                realSim[2] == null ? "" : realSim[2]));
    }

    private static String carrierClearStep(int sub) {
        return wrappedCarrierCall("carrier-clear",
                "app_process -Djava.class.path=$APK /system/bin " + BRIDGE
                        + " ccclear " + sub);
    }

    private static String autoTimeZoneCmd(boolean enabled) {
        return "{ cmd time_zone_detector set_auto_detection_enabled " + enabled
                + " 2>/dev/null || settings put global auto_time_zone "
                + (enabled ? "1" : "0") + "; }";
    }

    static String wrappedLocaleCall(String name, String call, boolean root) {
        if (root) {
            return call;
        }
        return "out=$(" + call + " 2>&1); rc=$?; "
                + "if [ $rc -ne 0 ]; then "
                + "case \"$out\" in "
                + "*\"SecurityException\"*|*\"Permission Denial\"*"
                + "|*\"Permission denied\"*|*\"permission denied\"*"
                + "|*\"not allowed\"*|*\"CHANGE_CONFIGURATION\"*"
                + "|*\"Operation not permitted\"*) "
                + "echo SKIP:" + name + " permission-denied; rc=0;; "
                + "*) echo \"$out\";; "
                + "esac; "
                + "else echo \"$out\"; fi; "
                + "[ $rc -eq 0 ]";
    }

    private static String wrappedCarrierCall(String name, String call) {
        if (!rootReady()) {
            return "out=$(" + call + " 2>&1); rc=$?; "
                    + "if [ $rc -ne 0 ]; then "
                    + "case \"$out\" in "
                    + "*\"cannot be invoked by shell\"*|*\"SecurityException\"*"
                    + "|*\"Permission Denial\"*|*\"Permission denied\"*|*\"permission denied\"*"
                    + "|*\"not allowed\"*|*\"MODIFY_PHONE_STATE\"*|*\"Operation not permitted\"*) "
                    + "echo SKIP:" + name + " permission-denied; rc=0;; "
                    + "*) echo \"$out\";; "
                    + "esac; "
                    + "else echo \"$out\"; fi; "
                    + "[ $rc -eq 0 ]";
        }
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

    static void observeCarrierDenial(String output) {
        if (output == null) {
            return;
        }
        if (output.contains("SKIP:carrier permission-denied")
                || output.contains("SKIP:carrier-name permission-denied")
                || output.contains("SKIP:carrier-clear permission-denied")
                || output.contains("SKIP:subname no-update SecurityException")
                || output.contains("cannot be invoked by shell")) {
            shellCarrierDenied = true;
        } else if (output.contains("cc set ") || output.contains("subname set ")) {
            shellCarrierDenied = false;
        }
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
