package com.walter.claudewilson;

import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.model.IpGuardPolicy;
import com.walter.claudewilson.model.ShellQuote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-instance persistent root daemon launched via {@code Bridge guard}.
 *
 * <p>Replaces shell polling and hardcoded sleeps with an event-driven, fail-closed state machine:
 * <ul>
 *   <li><b>Gate-First, Probe-Then-Release</b>: engages a target-UID-only {@code iptables REJECT}
 *       gate at t=0ms on any kernel route/link transition (foreign SIM Wi-Fi connect, dual-SIM
 *       switch, VPN tunnel rebuild/drop) or when the user enters the active proxy app.</li>
 *   <li><b>Zero Hardcoded Wait</b>: checks kernel FIB readiness ({@code ip route get 1.1.1.1})
 *       immediately and waits on the Netlink readiness condition only while no route exists,
 *       with generation-based cancellation ({@link #generation}) whenever a newer event arrives.</li>
 *   <li><b>Zero ART Cold-Start</b>: runs dual-source concurrent egress probes in-process via
 *       {@link Bridge#probeEgressFast()}, cutting verification latency to ~150-300ms.</li>
 *   <li><b>Auto-Recovery for IP Locks</b>: if a target was previously locked by Gate 8
 *       ({@code IP_FLIP}) and the user switches back to the expected region, automatically clears
 *       the lock and UID block once verified safe.</li>
 * </ul>
 */
final class IpGuardDaemon {
    private static final long TUN_DROP_DEADLINE_MS = 3000L;
    private static final long ROUTE_LOSS_DEADLINE_MS = 4000L;
    private static final long PROBE_RETRY_WAIT_MS = 350L;
    private static final long POLL_INTERVAL_MS = 60_000L;
    private static final long CONF_CHECK_INTERVAL_MS = 2_000L;

    static final String ACTION_BODYGUARD_CHANGED =
            "com.walter.claudewilson.BODYGUARD_STATE_CHANGED";

    private static final class DaemonConfig {
        final String mode;
        final String selfPkg;
        final String targetId;
        final String targetPkg;
        final String targetLabel;
        final String expectedIso;
        final IpGuardPolicy.Config ipCfg;
        final Set<String> restrictedIsos;
        final boolean expectProxy;
        final long mtime;

        DaemonConfig(String mode, String selfPkg, String targetId, String targetPkg,
                     String targetLabel, String expectedIso, IpGuardPolicy.Config ipCfg,
                     Set<String> restrictedIsos, boolean expectProxy, long mtime) {
            this.mode = mode;
            this.selfPkg = selfPkg;
            this.targetId = targetId;
            this.targetPkg = targetPkg;
            this.targetLabel = targetLabel;
            this.expectedIso = expectedIso;
            this.ipCfg = ipCfg;
            this.restrictedIsos = restrictedIsos;
            this.expectProxy = expectProxy;
            this.mtime = mtime;
        }
    }

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong(0L);
    private final Object coordLock = new Object();
    private final Object gateLock = new Object();

    private volatile DaemonConfig activeCfg;
    private volatile String dynamicVpnPkg = "";
    private volatile boolean inProxyApp = false;
    private volatile boolean tempGateEngaged = false;
    private volatile boolean dirtySinceLastVerify = true;
    private volatile long lastVerifiedOkMs = 0L;
    private volatile String cachedUidPkg = "";
    private volatile String cachedUid = "";

    private volatile Process netlinkProc;
    private volatile Process logcatProc;

    // Pending coordinator job state (protected by coordLock)
    private long pendingGen = 0L;
    private String pendingSource = "";
    private boolean pendingTunDrop = false;
    private boolean pendingAutoUnlockOnly = false;
    private boolean routePulse = false;

    private IpGuardDaemon() {
    }

    static void runMain() {
        new IpGuardDaemon().run();
    }

    private void run() {
        writePidFile();
        activeCfg = loadConfig(0L);
        if (activeCfg == null) {
            cleanupAndExit();
            return;
        }
        refreshDynamicVpnPkg();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                shutdownResources();
            }
        }, "ipg-shutdown"));

        Thread coordThread = new Thread(new Runnable() {
            @Override public void run() {
                coordinatorLoop();
            }
        }, "ipg-coord");
        coordThread.setDaemon(true);
        coordThread.start();

        Thread netlinkThread = new Thread(new Runnable() {
            @Override public void run() {
                netlinkLoop();
            }
        }, "ipg-netlink");
        netlinkThread.setDaemon(true);
        netlinkThread.start();

        Thread activityThread = new Thread(new Runnable() {
            @Override public void run() {
                activityLoop();
            }
        }, "ipg-activity");
        activityThread.setDaemon(true);
        activityThread.start();

        Thread pollThread = new Thread(new Runnable() {
            @Override public void run() {
                pollLoop();
            }
        }, "ipg-poll");
        pollThread.setDaemon(true);
        pollThread.start();

        // Main thread watches config hot-reload / shutdown sentinel cleanly.
        while (running.get()) {
            try {
                Thread.sleep(CONF_CHECK_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                break;
            }
            DaemonConfig cur = activeCfg;
            DaemonConfig next = loadConfig(cur == null ? 0L : cur.mtime);
            if (next == null) {
                // Config removed or MODE=OFF -> stop cleanly.
                break;
            }
            if (next != cur) {
                activeCfg = next;
                refreshDynamicVpnPkg();
            }
        }
        cleanupAndExit();
    }

    // ---- Monitor 1: Netlink Route & Link Events (0% CPU blocking read) ------------------

    private void netlinkLoop() {
        while (running.get()) {
            Process p = null;
            try {
                p = new ProcessBuilder("ip", "monitor", "link", "route")
                        .redirectErrorStream(true)
                        .start();
                netlinkProc = p;
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running.get() && (line = br.readLine()) != null) {
                    int kind = IpGuardPolicy.classifyNetlinkLine(line);
                    if (kind == IpGuardPolicy.NETLINK_IGNORE) {
                        continue;
                    }
                    onNetlinkEvent(kind == IpGuardPolicy.NETLINK_TUN_DROP);
                }
            } catch (Throwable ignored) {
            } finally {
                destroyQuiet(p);
                if (netlinkProc == p) {
                    netlinkProc = null;
                }
            }
            if (running.get()) {
                sleepQuiet(1500L);
            }
        }
    }

    private void onNetlinkEvent(boolean tunDrop) {
        DaemonConfig cfg = reloadIfChanged();
        if (cfg == null) {
            running.set(false);
            return;
        }
        dirtySinceLastVerify = true;
        boolean locked = isTargetLocked(cfg.targetId);
        if (!locked) {
            if (isTargetAlive(cfg.targetPkg)) {
                // t=0ms fail-closed gate: zero bytes can leak while route/tunnel settles.
                if (BodyguardPolicy.MODE_BLOCK_KILL.equals(cfg.mode)) {
                    engageTempGate(cfg.targetPkg);
                }
                scheduleVerification("netlink", tunDrop, false);
            } else {
                // Even if target isn't alive, wake any waiting readiness check and refresh VPN owner.
                pulseRouteCondition();
            }
        } else if (!tunDrop && isIpLocked(cfg.targetId)) {
            // Target was previously IP-locked; route/tunnel just came up -> check auto-recovery.
            scheduleVerification("netlink", false, true);
        } else {
            pulseRouteCondition();
        }
    }

    // ---- Monitor 2: Foreground Proxy & Target Start/Resume Monitor (0% CPU blocking) ----

    private void activityLoop() {
        while (running.get()) {
            Process p = null;
            try {
                // -T 1 skips old ring-buffer entries so daemon startup never replays stale switches.
                p = new ProcessBuilder("logcat", "-b", "events", "-T", "1",
                        "-s", "wm_set_resumed_activity", "am_focused_activity", "am_proc_start")
                        .redirectErrorStream(true)
                        .start();
                logcatProc = p;
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running.get() && (line = br.readLine()) != null) {
                    if (line.contains("am_proc_start")) {
                        String started = IpGuardPolicy.parseStartedPackageFromProcStartLine(line);
                        if (!started.isEmpty()) {
                            onProcessStarted(started);
                        }
                        continue;
                    }
                    String pkg = IpGuardPolicy.parseResumedPackageFromEventLine(line);
                    if (!pkg.isEmpty()) {
                        onForegroundPackage(pkg);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                destroyQuiet(p);
                if (logcatProc == p) {
                    logcatProc = null;
                }
            }
            if (running.get()) {
                sleepQuiet(2000L);
            }
        }
    }

    /**
     * Fires right as Zygote starts the target process (before Activity.onResume): engages a t=0ms
     * temporary UID gate and verifies live egress against the pinned baseline IP immediately so
     * switching proxy while the target was stopped never lets the target launch naked for 40s.
     */
    private void onProcessStarted(String startedPkg) {
        DaemonConfig cfg = reloadIfChanged();
        if (cfg == null) {
            running.set(false);
            return;
        }
        if (!cfg.targetPkg.equals(startedPkg)) {
            return;
        }
        if (isTargetLocked(cfg.targetId)) {
            return;
        }
        if (BodyguardPolicy.MODE_BLOCK_KILL.equals(cfg.mode)) {
            engageTempGate(cfg.targetPkg);
        }
        scheduleVerification("target_start", false, false);
    }

    private void onForegroundPackage(String pkg) {
        DaemonConfig cfg = reloadIfChanged();
        if (cfg == null) {
            running.set(false);
            return;
        }
        boolean isProxy = IpGuardPolicy.isKnownProxyPackage(pkg, dynamicVpnPkg);
        if (isProxy) {
            dirtySinceLastVerify = true;
            if (!inProxyApp) {
                inProxyApp = true;
                // t=0ms pre-shield BEFORE the user taps another node in Clash/V2rayNG/sing-box:
                // blocks background target telemetry so zero bytes can touch the new IP.
                if (BodyguardPolicy.MODE_BLOCK_KILL.equals(cfg.mode)
                        && !isTargetLocked(cfg.targetId)
                        && isTargetAlive(cfg.targetPkg)) {
                    engageTempGate(cfg.targetPkg);
                }
            }
            return;
        }
        if (inProxyApp) {
            inProxyApp = false;
            dirtySinceLastVerify = true;
            refreshDynamicVpnPkg();
            boolean locked = isTargetLocked(cfg.targetId);
            if (!locked && (tempGateEngaged || isTargetAlive(cfg.targetPkg))) {
                scheduleVerification("proxy_switch", false, false);
            } else if (locked && isIpLocked(cfg.targetId)) {
                scheduleVerification("proxy_switch", false, true);
            } else {
                releaseTempGateIfUnlocked(cfg);
            }
            return;
        }
        // Target brought to foreground while dirty (e.g. proxy was switched while target was
        // stopped, or >10s since last clean verification): gate & verify immediately unless
        // am_proc_start already engaged the gate moments ago.
        if (cfg.targetPkg.equals(pkg) && !isTargetLocked(cfg.targetId) && !tempGateEngaged) {
            long age = System.currentTimeMillis() - lastVerifiedOkMs;
            if (dirtySinceLastVerify || age > 10_000L) {
                if (BodyguardPolicy.MODE_BLOCK_KILL.equals(cfg.mode)) {
                    engageTempGate(cfg.targetPkg);
                }
                scheduleVerification("target_resume", false, false);
            }
        }
    }

    // ---- Monitor 3: Awake 60s Periodic Fallback (for upstream/router/WebUI switches) ----

    private void pollLoop() {
        while (running.get()) {
            sleepQuiet(POLL_INTERVAL_MS);
            if (!running.get()) {
                break;
            }
            DaemonConfig cfg = reloadIfChanged();
            if (cfg == null) {
                running.set(false);
                break;
            }
            if (!cfg.ipCfg.pollEveryMin || !cfg.ipCfg.needsCountryCheck()) {
                continue;
            }
            if (inProxyApp || tempGateEngaged) {
                continue;
            }
            if (isTargetLocked(cfg.targetId) || !isTargetAlive(cfg.targetPkg)) {
                continue;
            }
            if (!isScreenAwake()) {
                continue;
            }
            scheduleVerification("poll60", false, false);
        }
    }

    // ---- Central Event-Driven Coordinator (Zero Hardcoded Sleep + Generation Cancel) ----

    private void pulseRouteCondition() {
        synchronized (coordLock) {
            routePulse = true;
            coordLock.notifyAll();
        }
    }

    private void scheduleVerification(String source, boolean tunDrop, boolean autoUnlockOnly) {
        long g = generation.incrementAndGet();
        synchronized (coordLock) {
            pendingGen = g;
            pendingSource = source;
            pendingTunDrop = tunDrop;
            pendingAutoUnlockOnly = autoUnlockOnly;
            routePulse = true;
            coordLock.notifyAll();
        }
    }

    private void coordinatorLoop() {
        long lastHandledGen = 0L;
        while (running.get()) {
            final long myGen;
            final String source;
            final boolean tunDrop;
            final boolean autoUnlockOnly;
            synchronized (coordLock) {
                while (running.get() && (pendingGen <= lastHandledGen || inProxyApp)) {
                    try {
                        coordLock.wait(2000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                if (!running.get()) {
                    return;
                }
                myGen = pendingGen;
                source = pendingSource;
                tunDrop = pendingTunDrop;
                autoUnlockOnly = pendingAutoUnlockOnly;
                lastHandledGen = myGen;
            }
            handleVerification(myGen, source, tunDrop, autoUnlockOnly);
        }
    }

    private void handleVerification(long myGen, String source, boolean tunDrop,
                                    boolean autoUnlockOnly) {
        DaemonConfig cfg = reloadIfChanged();
        if (cfg == null) {
            running.set(false);
            return;
        }
        // Step 1: Event-driven readiness check (ZERO hardcoded sleep!).
        // Check kernel FIB + proxy presence immediately at t=0ms; only wait on the Netlink
        // condition when route/tunnel is genuinely absent (e.g., mid-DHCP or tunnel rebuild).
        long deadline = System.currentTimeMillis()
                + (tunDrop ? TUN_DROP_DEADLINE_MS : ROUTE_LOSS_DEADLINE_MS);
        boolean proxyUp = hasProxy();
        boolean routeUp = hasRoutableDefault();
        while (running.get() && isCurrentGen(myGen) && !inProxyApp
                && ((cfg.expectProxy && !proxyUp) || !routeUp)) {
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0L) {
                break;
            }
            synchronized (coordLock) {
                routePulse = false;
                try {
                    // Woken immediately at 0ms when ip monitor receives the new route/tunnel;
                    // capped at 300ms slice to also catch TProxy/iptables transitions cleanly.
                    coordLock.wait(Math.min(rem, 300L));
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            if (!isCurrentGen(myGen) || inProxyApp) {
                return;
            }
            proxyUp = hasProxy();
            routeUp = hasRoutableDefault();
        }
        if (!running.get() || !isCurrentGen(myGen) || inProxyApp) {
            return;
        }

        // Refresh VPN owner after tunnel changes so future proxy-app pre-shield stays accurate.
        if ("netlink".equals(source)) {
            refreshDynamicVpnPkg();
        }

        // Step 2: If proxy is still down after deadline while expected, evaluate immediately.
        if (cfg.expectProxy && !proxyUp) {
            if (autoUnlockOnly) {
                return;
            }
            IpGuardPolicy.Verdict dropV = IpGuardPolicy.evaluate(
                    cfg.mode, cfg.ipCfg, cfg.expectedIso, "", true, false, "", cfg.restrictedIsos);
            applyVerdict(cfg, dropV, "", "", source);
            return;
        }

        // Step 3: If only drop protection is enabled (no country check needed), release gate now!
        if (!cfg.ipCfg.needsCountryCheck()) {
            if (autoUnlockOnly) {
                if (proxyUp && routeUp) {
                    autoUnlockIpLock(cfg);
                }
            } else {
                releaseTempGateIfUnlocked(cfg);
            }
            return;
        }

        // Step 4: Dual-source concurrent egress race in-process (zero ART cold-start!).
        String[] eg = Bridge.probeEgressFast();
        if ((eg[1] == null || eg[1].isEmpty()) && isCurrentGen(myGen) && !inProxyApp && routeUp) {
            // Transient DNS/socket settle right after route install: one tight event-aware retry.
            synchronized (coordLock) {
                try {
                    coordLock.wait(PROBE_RETRY_WAIT_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            if (!isCurrentGen(myGen) || inProxyApp) {
                return;
            }
            proxyUp = hasProxy();
            if (cfg.expectProxy && !proxyUp) {
                if (!autoUnlockOnly) {
                    IpGuardPolicy.Verdict dropV = IpGuardPolicy.evaluate(
                            cfg.mode, cfg.ipCfg, cfg.expectedIso, "", true, false, "",
                            cfg.restrictedIsos);
                    applyVerdict(cfg, dropV, "", "", source);
                }
                return;
            }
            eg = Bridge.probeEgressFast();
        }
        if (!isCurrentGen(myGen) || inProxyApp) {
            // Superseded by a newer network/proxy event -> discard stale probe result!
            return;
        }

        String liveIp = eg[0] == null ? "" : eg[0].trim();
        String liveIso = IpGuardPolicy.normalizeIso(eg[1]);

        if (autoUnlockOnly) {
            if (!liveIso.isEmpty()) {
                String[] pinned = readLastTargetIp(cfg.targetId);
                String pinnedIp = pinned[0];
                if (pinnedIp.isEmpty()) {
                    String lockReason = readSmallText(
                            new File(IpGuardPolicy.lockReasonPath(cfg.targetId)));
                    pinnedIp = IpGuardPolicy.parsePrevIpFromFlipSignal(lockReason);
                }
                if (!pinnedIp.isEmpty()) {
                    // Baseline IP is pinned from first use: only auto-unlock when the exact
                    // original baseline IP returns. Adopting a new IP requires explicit user
                    // confirmation in the Unlock baseline-switch prompt.
                    if (pinnedIp.equals(liveIp) && liveIso.equals(cfg.expectedIso)
                            && (!cfg.expectProxy || proxyUp)) {
                        autoUnlockIpLock(cfg);
                    }
                    return;
                }
                IpGuardPolicy.Verdict rec = IpGuardPolicy.evaluate(
                        BodyguardPolicy.MODE_BLOCK_KILL, cfg.ipCfg, cfg.expectedIso, liveIso,
                        cfg.expectProxy, proxyUp, "", cfg.restrictedIsos);
                if (!rec.isKill() && liveIso.equals(cfg.expectedIso)) {
                    if (!liveIp.isEmpty()) {
                        writeLastTargetIp(cfg.targetId, liveIp, liveIso);
                    }
                    autoUnlockIpLock(cfg);
                }
            }
            return;
        }

        if (liveIso.isEmpty()) {
            // Probe unreachable while route/proxy exists: do not strand user on a temp gate
            // unless proxy actually dropped; re-check proxyUp before releasing.
            if (cfg.expectProxy && !hasProxy()) {
                IpGuardPolicy.Verdict dropV = IpGuardPolicy.evaluate(
                        cfg.mode, cfg.ipCfg, cfg.expectedIso, "", true, false, "",
                        cfg.restrictedIsos);
                applyVerdict(cfg, dropV, "", "", source);
            } else {
                releaseTempGateIfUnlocked(cfg);
            }
            return;
        }

        String[] last = readLastTargetIp(cfg.targetId);
        IpGuardPolicy.Verdict verdict = IpGuardPolicy.evaluateForTarget(
                cfg.mode, cfg.ipCfg, cfg.expectedIso,
                last[0], last[1], liveIp, liveIso,
                cfg.expectProxy, proxyUp, "", cfg.restrictedIsos);
        applyVerdict(cfg, verdict, liveIp, liveIso, source);
    }

    private void applyVerdict(DaemonConfig cfg, IpGuardPolicy.Verdict v,
                              String liveIp, String liveIso, String source) {
        if (v.isKill()) {
            String msg;
            if ("vpn_down".equals(v.kind)) {
                msg = "Proxy dropped - network blocked";
            } else if ("restricted".equals(v.kind)) {
                msg = "IP flipped to restricted " + liveIso;
            } else if ("ip_flip".equals(v.kind)) {
                String pair = v.signalKey.startsWith("ip:flip:")
                        ? v.signalKey.substring("ip:flip:".length()).replace("->", " -> ")
                        : liveIp;
                msg = "IP flipped (" + pair + ") - network blocked";
            } else {
                msg = "IP changed " + v.expectedIso + " -> " + v.liveIso;
            }
            emitIncident(cfg, "KILLED", v.signalKey, source, msg);
            return;
        }
        if (v.isWarn()) {
            String msg = "";
            if ("vpn_down".equals(v.kind)) {
                msg = "Proxy dropped";
            } else if ("restricted".equals(v.kind)) {
                msg = "IP in restricted " + liveIso;
            } else if ("ip_flip".equals(v.kind)) {
                String pair = v.signalKey.startsWith("ip:flip:")
                        ? v.signalKey.substring("ip:flip:".length()).replace("->", " -> ")
                        : liveIp;
                msg = "IP changed (" + pair + ")";
            } else if ("drift".equals(v.kind)) {
                msg = "IP changed " + v.expectedIso + " -> " + v.liveIso;
            }
            emitIncident(cfg, "OBSERVED", v.signalKey, source, msg);
        }
        if (!liveIp.isEmpty() && !liveIso.isEmpty()) {
            // Pin the baseline IP strictly on first use; never silently overwrite an existing
            // baseline IP (only explicit user confirmation on Unlock or a region switch may update it).
            String[] existing = readLastTargetIp(cfg.targetId);
            if (existing[0].isEmpty()) {
                writeLastTargetIp(cfg.targetId, liveIp, liveIso);
            }
        }
        if (v.isAllow()) {
            dirtySinceLastVerify = false;
            lastVerifiedOkMs = System.currentTimeMillis();
        }
        releaseTempGateIfUnlocked(cfg);
    }

    private boolean isCurrentGen(long myGen) {
        return generation.get() == myGen;
    }

    // ---- Enforcement, Incident Persistence & Auto-Unlock --------------------------------

    private void engageTempGate(String targetPkg) {
        synchronized (gateLock) {
            if (tempGateEngaged) {
                return;
            }
            if (blockTargetUid(targetPkg)) {
                tempGateEngaged = true;
            }
        }
    }

    private void releaseTempGateIfUnlocked(DaemonConfig cfg) {
        synchronized (gateLock) {
            if (!tempGateEngaged) {
                return;
            }
            if (cfg != null && isTargetLocked(cfg.targetId)) {
                tempGateEngaged = false;
                return;
            }
            unblockTargetUid(cfg == null ? cachedUidPkg : cfg.targetPkg);
            tempGateEngaged = false;
        }
    }

    private void autoUnlockIpLock(DaemonConfig cfg) {
        synchronized (gateLock) {
            File lf = new File(IpGuardPolicy.LOCK_DIR, cfg.targetId + ".lock");
            File rf = new File(IpGuardPolicy.lockReasonPath(cfg.targetId));
            File uf = new File(IpGuardPolicy.LOCK_DIR, cfg.targetId + ".unlock");
            lf.delete();
            rf.delete();
            writeTextFile(uf, String.valueOf(System.currentTimeMillis()));
            unblockTargetUid(cfg.targetPkg);
            tempGateEngaged = false;
        }
        notifyUiChanged(cfg.selfPkg);
    }

    private void emitIncident(DaemonConfig cfg, String action, String signalKey,
                              String source, String notifMsg) {
        long nowMs = System.currentTimeMillis();
        File evFile = new File(IpGuardPolicy.netEventPath(cfg.targetId));
        String line = nowMs + "\tCWBG1|" + action + "|" + BodyguardPolicy.GATE_IP_FLIP
                + "|" + signalKey + "|" + source + "|0\n";
        appendCappedLines(evFile, line, 30);

        if ("KILLED".equals(action)) {
            synchronized (gateLock) {
                blockTargetUid(cfg.targetPkg);
                tempGateEngaged = false;
                new File(IpGuardPolicy.LOCK_DIR, cfg.targetId + ".unlock").delete();
                writeTextFile(new File(IpGuardPolicy.LOCK_DIR, cfg.targetId + ".lock"),
                        String.valueOf(nowMs));
                writeTextFile(new File(IpGuardPolicy.lockReasonPath(cfg.targetId)), signalKey);
            }
            runQuiet("am", "force-stop", cfg.targetPkg);
            notifyUiChanged(cfg.selfPkg);
            postNotification(cfg.targetLabel + " Paused", "cw_ip_" + cfg.targetId, notifMsg);
        } else {
            notifyUiChanged(cfg.selfPkg);
            if (notifMsg != null && !notifMsg.isEmpty()) {
                postNotification(cfg.targetLabel + " IP Alert", "cw_ip_" + cfg.targetId, notifMsg);
            }
        }
    }

    /** Pushes an immediate package-scoped broadcast so foreground Dashboard repaints at once. */
    private void notifyUiChanged(String selfPkg) {
        if (selfPkg == null || !ShellQuote.packageName(selfPkg)) {
            return;
        }
        runQuiet("am", "broadcast", "-a", ACTION_BODYGUARD_CHANGED, "-p", selfPkg);
    }

    private boolean blockTargetUid(String targetPkg) {
        String uid = resolveUid(targetPkg);
        if (uid.isEmpty()) {
            return false;
        }
        String chain = IpGuardPolicy.IPTABLES_CHAIN;
        String cmd = "for IPT in iptables ip6tables; do "
                + "$IPT -N " + chain + " 2>/dev/null || true; "
                + "$IPT -C OUTPUT -j " + chain + " 2>/dev/null || $IPT -I OUTPUT 1 -j " + chain + " 2>/dev/null || true; "
                + "$IPT -C " + chain + " -m owner --uid-owner " + uid + " -j REJECT 2>/dev/null || "
                + "$IPT -I " + chain + " 1 -m owner --uid-owner " + uid + " -j REJECT 2>/dev/null || true; "
                + "done";
        runShell(cmd);
        return true;
    }

    private void unblockTargetUid(String targetPkg) {
        String uid = resolveUid(targetPkg);
        if (uid.isEmpty()) {
            return;
        }
        String chain = IpGuardPolicy.IPTABLES_CHAIN;
        String cmd = "for IPT in iptables ip6tables; do "
                + "while $IPT -D " + chain + " -m owner --uid-owner " + uid + " -j REJECT 2>/dev/null; do :; done; "
                + "done";
        runShell(cmd);
    }

    private void postNotification(String title, String tag, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        String t = title.replace('\'', ' ').replace('"', ' ');
        String tg = tag.replace('\'', ' ').replace('"', ' ');
        String b = body.replace('\'', ' ').replace('"', ' ');
        runShell("su 2000 -c \"cmd notification post -t '" + t + "' '" + tg + "' '" + b + "'\" "
                + ">/dev/null 2>&1 || cmd notification post -t \"" + t + "\" \"" + tg + "\" \"" + b + "\" >/dev/null 2>&1 || true");
    }

    // ---- Fast Pure-Java OS Probes (Minimal Subprocess Overhead) -------------------------

    private boolean hasProxy() {
        // 1. Fast pure-filesystem check for VPN/WireGuard/Tailscale tunnels (<0.5ms, zero fork).
        File netDir = new File("/sys/class/net");
        String[] ifaces = netDir.list();
        if (ifaces != null) {
            for (String name : ifaces) {
                if (IpGuardPolicy.isTunnelInterfaceName(name)) {
                    String st = readSmallText(new File(netDir, name + "/operstate"));
                    if (!"down".equalsIgnoreCase(st)) {
                        return true;
                    }
                }
            }
        }
        // 2. Fallback for transparent proxy (TProxy/REDIRECT) setups without a tun device.
        String nat = runShellCapture("iptables -t nat -S 2>/dev/null; iptables -t mangle -S 2>/dev/null");
        return nat.contains("-j REDIRECT") || nat.contains("-j TPROXY");
    }

    private boolean hasRoutableDefault() {
        String out = runExecCapture("ip", "route", "get", "1.1.1.1");
        return out.contains("dev ") && !out.contains("unreachable");
    }

    private boolean isScreenAwake() {
        String out = runShellCapture("dumpsys power 2>/dev/null | grep -m1 'mWakefulness='");
        return out.contains("mWakefulness=Awake");
    }

    private void refreshDynamicVpnPkg() {
        String out = runShellCapture("dumpsys connectivity 2>/dev/null | grep -m4 'VPN:'");
        String pkg = IpGuardPolicy.parseVpnPackageFromConnectivity(out);
        if (!pkg.isEmpty()) {
            dynamicVpnPkg = pkg;
        }
    }

    /** Pure /proc scan to test whether targetPkg currently has a live process (~1-3ms, zero fork). */
    private boolean isTargetAlive(String targetPkg) {
        if (targetPkg == null || targetPkg.isEmpty()) {
            return false;
        }
        File proc = new File("/proc");
        String[] names = proc.list();
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') {
                continue;
            }
            String cmd = readProcCmdline(new File(proc, name + "/cmdline"));
            if (targetPkg.equals(cmd)) {
                return true;
            }
        }
        return false;
    }

    private String resolveUid(String targetPkg) {
        if (targetPkg == null || !ShellQuote.packageName(targetPkg)) {
            return "";
        }
        if (targetPkg.equals(cachedUidPkg) && !cachedUid.isEmpty()) {
            return cachedUid;
        }
        // Fast pure-file lookup via /data/system/packages.list (<1ms).
        File pkgList = new File("/data/system/packages.list");
        if (pkgList.isFile() && pkgList.canRead()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(pkgList), StandardCharsets.UTF_8));
                String line;
                String prefix = targetPkg + " ";
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(prefix)) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && parts[1].matches("\\d+")) {
                            cachedUidPkg = targetPkg;
                            cachedUid = parts[1];
                            return cachedUid;
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuiet(br);
            }
        }
        String out = runShellCapture("pm list packages -U " + targetPkg
                + " 2>/dev/null | sed -n 's/.*uid://p' | head -1").trim();
        if (out.matches("\\d+")) {
            cachedUidPkg = targetPkg;
            cachedUid = out;
            return cachedUid;
        }
        return "";
    }

    private boolean isTargetLocked(String targetId) {
        File lf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".lock");
        File uf = new File(IpGuardPolicy.LOCK_DIR, targetId + ".unlock");
        long lt = lf.isFile() ? lf.lastModified() : 0L;
        long ut = uf.isFile() ? uf.lastModified() : 0L;
        return BodyguardPolicy.isLockEffective(0L, lt, ut);
    }

    private boolean isIpLocked(String targetId) {
        String reason = readSmallText(new File(IpGuardPolicy.lockReasonPath(targetId)));
        return IpGuardPolicy.isIpFlipSignal(reason);
    }

    private String[] readLastTargetIp(String targetId) {
        String raw = readSmallText(new File(IpGuardPolicy.lastTargetIpPath(targetId)));
        if (raw.isEmpty()) {
            return new String[]{"", ""};
        }
        int bar = raw.indexOf('|');
        if (bar < 0) {
            return new String[]{raw.trim(), ""};
        }
        return new String[]{raw.substring(0, bar).trim(),
                IpGuardPolicy.normalizeIso(raw.substring(bar + 1))};
    }

    private void writeLastTargetIp(String targetId, String ip, String cc) {
        writeTextFile(new File(IpGuardPolicy.lastTargetIpPath(targetId)), ip + "|" + cc);
    }

    // ---- Config Loading & Lifecycle Cleanup ---------------------------------------------

    private DaemonConfig reloadIfChanged() {
        DaemonConfig cur = activeCfg;
        DaemonConfig next = loadConfig(cur == null ? 0L : cur.mtime);
        if (next != cur) {
            activeCfg = next;
        }
        return next;
    }

    private DaemonConfig loadConfig(long lastMtime) {
        File f = new File(IpGuardPolicy.CONF_PATH);
        if (!f.isFile() || !f.canRead()) {
            return null;
        }
        long mtime = f.lastModified();
        if (lastMtime > 0L && mtime == lastMtime && activeCfg != null) {
            return activeCfg;
        }
        String mode = BodyguardPolicy.MODE_OFF;
        String selfPkg = "";
        String targetId = "";
        String targetPkg = "";
        String targetLabel = "";
        String expectedIso = "";
        boolean killDrop = true;
        boolean killRest = true;
        String drift = IpGuardPolicy.DRIFT_KILL;
        boolean pollMin = true;
        boolean expectProxy = false;
        Set<String> restricted = new LinkedHashSet<String>();

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("MODE".equals(k)) mode = BodyguardPolicy.normalizeMode(v);
                else if ("SELF_PKG".equals(k)) selfPkg = v;
                else if ("TARGET_ID".equals(k)) targetId = v;
                else if ("TARGET_PKG".equals(k)) targetPkg = v;
                else if ("TARGET_LABEL".equals(k)) targetLabel = v;
                else if ("EXPECTED_ISO".equals(k)) expectedIso = IpGuardPolicy.normalizeIso(v);
                else if ("KILL_ON_DROP".equals(k)) killDrop = "1".equals(v);
                else if ("KILL_ON_RESTRICTED".equals(k)) killRest = "1".equals(v);
                else if ("DRIFT_POLICY".equals(k)) drift = IpGuardPolicy.normalizeDriftPolicy(v);
                else if ("POLL_MIN".equals(k)) pollMin = "1".equals(v);
                else if ("EXPECT_PROXY".equals(k)) expectProxy = "1".equals(v);
                else if ("RESTRICTED_CSV".equals(k) && !v.isEmpty()) {
                    for (String iso : v.split(",")) {
                        String norm = IpGuardPolicy.normalizeIso(iso);
                        if (!norm.isEmpty()) restricted.add(norm);
                    }
                }
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuiet(br);
        }

        IpGuardPolicy.Config ipCfg = new IpGuardPolicy.Config(killDrop, killRest, drift, pollMin);
        if (BodyguardPolicy.MODE_OFF.equals(mode) || !ipCfg.anyEnabled()
                || targetId.isEmpty() || !ShellQuote.packageName(targetPkg)) {
            return null;
        }
        return new DaemonConfig(mode, selfPkg, targetId, targetPkg,
                targetLabel.isEmpty() ? targetId : targetLabel,
                expectedIso, ipCfg, Collections.unmodifiableSet(restricted),
                expectProxy, mtime);
    }

    private void writePidFile() {
        writeTextFile(new File(IpGuardPolicy.PID_PATH),
                String.valueOf(android.os.Process.myPid()));
    }

    private void cleanupAndExit() {
        running.set(false);
        synchronized (coordLock) {
            coordLock.notifyAll();
        }
        shutdownResources();
        new File(IpGuardPolicy.PID_PATH).delete();
    }

    private void shutdownResources() {
        destroyQuiet(netlinkProc);
        destroyQuiet(logcatProc);
        releaseTempGateIfUnlocked(activeCfg);
    }

    // ---- Small File & Process Utilities -------------------------------------------------

    private static String readProcCmdline(File file) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            closeQuiet(in);
        }
    }

    private static String readSmallText(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return "";
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            closeQuiet(in);
        }
    }

    private static void writeTextFile(File file, String content) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            out = null;
            tmp.setReadable(true, false);
            if (!tmp.renameTo(file)) {
                // Fallback direct write if rename across mount fails.
                FileOutputStream direct = new FileOutputStream(file, false);
                try {
                    direct.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
                } finally {
                    closeQuiet(direct);
                }
                tmp.delete();
            }
            file.setReadable(true, false);
        } catch (Throwable ignored) {
            tmp.delete();
        } finally {
            closeQuiet(out);
        }
    }

    private static void appendCappedLines(File file, String newLine, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (file.isFile() && file.canRead()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8));
                String l;
                while ((l = br.readLine()) != null) {
                    if (!l.trim().isEmpty()) {
                        lines.add(l);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuiet(br);
            }
        }
        lines.add(newLine.trim());
        int start = Math.max(0, lines.size() - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        writeTextFile(file, sb.toString());
    }

    private static void runShell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable ignored) {
        } finally {
            destroyQuiet(p);
        }
    }

    private static void runQuiet(String... argv) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable ignored) {
        } finally {
            destroyQuiet(p);
        }
    }

    private static String runShellCapture(String cmd) {
        return runExecCapture("sh", "-c", cmd);
    }

    private static String runExecCapture(String... argv) {
        Process p = null;
        BufferedReader br = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            br = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && sb.length() < 8192) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            closeQuiet(br);
            destroyQuiet(p);
        }
    }

    private static void destroyQuiet(Process p) {
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
