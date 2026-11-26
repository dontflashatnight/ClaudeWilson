package com.walter.claudewilson.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure policy, decision engine, and root-shell watchdog builder for Bodyguard Gate 8
 * ({@link BodyguardPolicy#GATE_IP_FLIP}). Zero Android imports, zero CJK.
 *
 * <p>Protects the target application from uncontrollable external network flips:
 * <ul>
 *   <li>Proxy/VPN drop or split-tunnel direct bypass to local interface;</li>
 *   <li>Egress country flip into a restricted region (e.g. CN/HK/MO/RU);</li>
 *   <li>Non-restricted cross-country drift (configurable: OFF / WARN / KILL);</li>
 *   <li>Same-country dynamic IP rotation is always allowed (zero false kills).</li>
 * </ul>
 */
public final class IpGuardPolicy {

    public static final String DRIFT_OFF = "OFF";
    public static final String DRIFT_WARN = "WARN";
    public static final String DRIFT_KILL = "KILL";

    public static final String ACTION_ALLOW = "ALLOW";
    public static final String ACTION_WARN = "OBSERVED";
    public static final String ACTION_KILL = "KILLED";

    public static final String DIR = "/data/local/tmp/claudewilson";
    public static final String LOCK_DIR = DIR + "/lock";
    public static final String CONF_PATH = DIR + "/ip-guard.conf";
    public static final String SCRIPT_PATH = DIR + "/ip-guard.sh";
    public static final String PID_PATH = DIR + "/ip-guard.pid";
    public static final String IPTABLES_CHAIN = "cw_bg_drop";

    /** User-approved defaults: strict protection enabled out of the box. */
    public static final boolean DEFAULT_KILL_ON_DROP = true;
    public static final boolean DEFAULT_KILL_ON_RESTRICTED = true;
    public static final String DEFAULT_DRIFT_POLICY = DRIFT_KILL;
    public static final boolean DEFAULT_POLL_EVERY_MIN = true;

    private IpGuardPolicy() {
    }

    public static final class Config {
        public final boolean killOnDrop;
        public final boolean killOnRestricted;
        public final String driftPolicy;
        public final boolean pollEveryMin;

        public Config(boolean killOnDrop, boolean killOnRestricted,
                      String driftPolicy, boolean pollEveryMin) {
            this.killOnDrop = killOnDrop;
            this.killOnRestricted = killOnRestricted;
            this.driftPolicy = normalizeDriftPolicy(driftPolicy);
            this.pollEveryMin = pollEveryMin;
        }

        public static Config defaults() {
            return new Config(DEFAULT_KILL_ON_DROP, DEFAULT_KILL_ON_RESTRICTED,
                    DEFAULT_DRIFT_POLICY, DEFAULT_POLL_EVERY_MIN);
        }

        public boolean needsCountryCheck() {
            return killOnRestricted || !DRIFT_OFF.equals(driftPolicy);
        }

        public boolean anyEnabled() {
            return killOnDrop || needsCountryCheck();
        }
    }

    public static final class Verdict {
        public final String action;
        public final String signalKey;
        public final String kind;
        public final String liveIso;
        public final String expectedIso;

        public Verdict(String action, String signalKey, String kind,
                       String liveIso, String expectedIso) {
            this.action = nn(action);
            this.signalKey = nn(signalKey);
            this.kind = nn(kind);
            this.liveIso = nn(liveIso);
            this.expectedIso = nn(expectedIso);
        }

        public boolean isKill() {
            return ACTION_KILL.equals(action);
        }

        public boolean isWarn() {
            return ACTION_WARN.equals(action);
        }

        public boolean isAllow() {
            return ACTION_ALLOW.equals(action);
        }
    }

    /** Normalizes stored cross-country drift policy; unknown defaults to {@link #DRIFT_KILL}. */
    public static String normalizeDriftPolicy(String raw) {
        if (raw == null) {
            return DEFAULT_DRIFT_POLICY;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (DRIFT_OFF.equals(u) || DRIFT_WARN.equals(u) || DRIFT_KILL.equals(u)) {
            return u;
        }
        return DEFAULT_DRIFT_POLICY;
    }

    /** Normalizes a two-letter ISO country code, or returns empty if invalid. */
    public static String normalizeIso(String raw) {
        if (raw == null) {
            return "";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return u.matches("[A-Z]{2}") ? u : "";
    }

    /**
     * Evaluates a live network transition or egress check against the active Bodyguard mode and
     * {@link Config}. Same-country IP rotation always returns {@link #ACTION_ALLOW}.
     */
    public static Verdict evaluate(String guardMode, Config cfg,
                                   String expectedIso, String liveIso,
                                   boolean expectProxy, boolean proxyUp,
                                   String directBypassHost,
                                   Set<String> restrictedIsos) {
        String mode = BodyguardPolicy.normalizeMode(guardMode);
        Config c = cfg == null ? Config.defaults() : cfg;
        String exp = normalizeIso(expectedIso);
        String live = normalizeIso(liveIso);
        if (BodyguardPolicy.MODE_OFF.equals(mode) || !c.anyEnabled()) {
            return new Verdict(ACTION_ALLOW, "", "off", live, exp);
        }
        boolean allowKill = BodyguardPolicy.MODE_BLOCK_KILL.equals(mode);

        // 1. Proxy dropped while an active local proxy was expected.
        if (expectProxy && !proxyUp && c.killOnDrop) {
            return new Verdict(allowKill ? ACTION_KILL : ACTION_WARN,
                    "net:vpn-down", "vpn_down", live, exp);
        }

        // 2. Direct split-tunnel bypass on a target outbound host.
        String bypass = directBypassHost == null ? "" : directBypassHost.trim();
        if (expectProxy && !bypass.isEmpty() && c.killOnDrop) {
            String host = BodyguardEventCodec.normalizeHost(bypass);
            String key = host.isEmpty() ? "egress:direct" : "egress:direct:" + host;
            return new Verdict(allowKill ? ACTION_KILL : ACTION_WARN,
                    key, "direct_bypass", live, exp);
        }

        // 3. Country-level checks (restricted region or non-restricted drift).
        if (!live.isEmpty()) {
            boolean restricted = isInSet(restrictedIsos, live);
            if (restricted && c.killOnRestricted) {
                String key = exp.isEmpty() ? "egress:restricted:" + live : "egress:" + exp + "->" + live;
                return new Verdict(allowKill ? ACTION_KILL : ACTION_WARN,
                        key, "restricted", live, exp);
            }
            if (!exp.isEmpty() && !live.equals(exp)) {
                if (DRIFT_KILL.equals(c.driftPolicy)) {
                    return new Verdict(allowKill ? ACTION_KILL : ACTION_WARN,
                            "egress:" + exp + "->" + live, "drift", live, exp);
                }
                if (DRIFT_WARN.equals(c.driftPolicy)) {
                    return new Verdict(ACTION_WARN,
                            "egress:" + exp + "->" + live, "drift", live, exp);
                }
            }
        }

        // Same-country IP rotation (or no violation) is always allowed.
        return new Verdict(ACTION_ALLOW, "", "ok", live, exp);
    }

    /**
     * Target-process-anchored evaluation: compares the target process's live {@code (liveIp, liveIso)}
     * against the target process's own last observed {@code (lastTargetIp, lastTargetIso)} (never
     * against unrelated VPN switches while the target was closed).
     *
     * <p>Any mid-session IP change on an active target process (including same-country IP flips)
     * triggers a circuit-breaker kill in {@link BodyguardPolicy#MODE_BLOCK_KILL} and an alert in
     * {@link BodyguardPolicy#MODE_OBSERVE}.
     */
    public static Verdict evaluateForTarget(String guardMode, Config cfg,
                                            String expectedIso,
                                            String lastTargetIp, String lastTargetIso,
                                            String liveIp, String liveIso,
                                            boolean expectProxy, boolean proxyUp,
                                            String directBypassHost,
                                            Set<String> restrictedIsos) {
        String baseIso = normalizeIso(lastTargetIso);
        if (baseIso.isEmpty()) {
            baseIso = normalizeIso(expectedIso);
        }
        Verdict base = evaluate(guardMode, cfg, baseIso, liveIso,
                expectProxy, proxyUp, directBypassHost, restrictedIsos);
        if (!base.isAllow()) {
            return base;
        }
        // Also check against expectedIso when lastTargetIso differed from expectedIso.
        String exp = normalizeIso(expectedIso);
        if (!exp.isEmpty() && !exp.equals(baseIso)) {
            Verdict expV = evaluate(guardMode, cfg, exp, liveIso,
                    expectProxy, proxyUp, directBypassHost, restrictedIsos);
            if (!expV.isAllow()) {
                return expV;
            }
        }
        String prevIp = lastTargetIp == null ? "" : lastTargetIp.trim();
        String curIp = liveIp == null ? "" : liveIp.trim();
        String mode = BodyguardPolicy.normalizeMode(guardMode);
        Config c = cfg == null ? Config.defaults() : cfg;
        if (!BodyguardPolicy.MODE_OFF.equals(mode) && c.anyEnabled()
                && !prevIp.isEmpty() && !curIp.isEmpty() && !prevIp.equals(curIp)) {
            String act = BodyguardPolicy.MODE_BLOCK_KILL.equals(mode)
                    ? ACTION_KILL : ACTION_WARN;
            return new Verdict(act, "ip:flip:" + prevIp + "->" + curIp,
                    "ip_flip", normalizeIso(liveIso), baseIso);
        }
        return base;
    }

    /** Extracts the pre-flip IP (e.g. "1.1.1.1") from {@code ip:flip:1.1.1.1->2.2.2.2}, else "". */
    public static String parsePrevIpFromFlipSignal(String signalKey) {
        if (signalKey == null || !signalKey.startsWith("ip:flip:")) {
            return "";
        }
        int arrow = signalKey.indexOf("->", "ip:flip:".length());
        if (arrow > "ip:flip:".length()) {
            return signalKey.substring("ip:flip:".length(), arrow).trim();
        }
        return "";
    }

    /** Extracts expected ISO (e.g. "US") from an {@code egress:US->JP} signal key, else "". */
    public static String parseExpectedIsoFromSignal(String signalKey) {
        if (signalKey == null || !signalKey.startsWith("egress:")) {
            return "";
        }
        int arrow = signalKey.indexOf("->");
        if (arrow > "egress:".length()) {
            return normalizeIso(signalKey.substring("egress:".length(), arrow));
        }
        return "";
    }

    /** Extracts target drift ISO (e.g. "JP") from an {@code egress:US->JP} signal key, else "". */
    public static String parseTargetIsoFromSignal(String signalKey) {
        if (signalKey == null) {
            return "";
        }
        int arrow = signalKey.indexOf("->");
        if (arrow > 0 && arrow + 2 < signalKey.length()) {
            return normalizeIso(signalKey.substring(arrow + 2));
        }
        if (signalKey.startsWith("egress:restricted:")) {
            return normalizeIso(signalKey.substring("egress:restricted:".length()));
        }
        return "";
    }

    /** True for signal keys produced by {@link BodyguardPolicy#GATE_IP_FLIP}. */
    public static boolean isIpFlipSignal(String signalKey) {
        if (signalKey == null) {
            return false;
        }
        return "net:vpn-down".equals(signalKey)
                || signalKey.startsWith("egress:direct")
                || signalKey.startsWith("egress:")
                || signalKey.startsWith("ip:flip:");
    }

    /** Netlink line classification for event-driven route/tunnel gating. */
    public static final int NETLINK_IGNORE = 0;
    public static final int NETLINK_ROUTE_CHANGE = 1;
    public static final int NETLINK_TUN_DROP = 2;

    /** Formats the per-target last observed target-process IP file (outside target directories). */
    public static String lastTargetIpPath(String targetId) {
        String id = safeId(targetId);
        return LOCK_DIR + "/" + id + ".lastip";
    }

    /** Formats the per-target lock-reason metadata file (used to distinguish IP locks on auto-recovery). */
    public static String lockReasonPath(String targetId) {
        String id = safeId(targetId);
        return LOCK_DIR + "/" + id + ".lockreason";
    }

    /**
     * Parses the resumed foreground package name from an Android events logcat line
     * ({@code wm_set_resumed_activity} or {@code am_focused_activity}).
     */
    public static String parseResumedPackageFromEventLine(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (!line.contains("wm_set_resumed_activity") && !line.contains("am_focused_activity")) {
            return "";
        }
        int lb = line.indexOf('[');
        int rb = line.indexOf(']', lb + 1);
        if (lb < 0 || rb <= lb) {
            return "";
        }
        String body = line.substring(lb + 1, rb);
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            int slash = trimmed.indexOf('/');
            if (slash > 0) {
                String pkg = trimmed.substring(0, slash).trim();
                if (ShellQuote.packageName(pkg)) {
                    return pkg;
                }
            }
        }
        return "";
    }

    /**
     * Parses the started process/package name from an Android {@code am_proc_start} events line,
     * e.g. {@code am_proc_start: [0,15111,10391,com.anthropic.claude,next-top-activity,...]}.
     */
    public static String parseStartedPackageFromProcStartLine(String line) {
        if (line == null || !line.contains("am_proc_start")) {
            return "";
        }
        int lb = line.indexOf('[');
        int rb = line.indexOf(']', lb + 1);
        if (lb < 0 || rb <= lb) {
            return "";
        }
        String[] parts = line.substring(lb + 1, rb).split(",");
        if (parts.length < 4) {
            return "";
        }
        String proc = parts[3].trim();
        int colon = proc.indexOf(':');
        if (colon > 0) {
            proc = proc.substring(0, colon);
        }
        return ShellQuote.packageName(proc) ? proc : "";
    }

    /** Extracts the active Android VpnService owner package from {@code dumpsys connectivity}. */
    public static String parseVpnPackageFromConnectivity(String dumpsysText) {
        if (dumpsysText == null || dumpsysText.isEmpty()) {
            return "";
        }
        int idx = 0;
        while (idx >= 0) {
            int pos = dumpsysText.indexOf("VPN:", idx);
            if (pos < 0) {
                return "";
            }
            int start = pos + 4;
            int end = start;
            while (end < dumpsysText.length()) {
                char ch = dumpsysText.charAt(end);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_') {
                    end++;
                } else {
                    break;
                }
            }
            String candidate = dumpsysText.substring(start, end);
            if (ShellQuote.packageName(candidate) && candidate.contains(".")) {
                return candidate;
            }
            idx = end;
        }
        return "";
    }

    /** True when {@code pkg} is the active VPN host or a known Android proxy/VPN controller app. */
    public static boolean isKnownProxyPackage(String pkg, String dynamicVpnPkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        String p = pkg.trim();
        if (dynamicVpnPkg != null && !dynamicVpnPkg.isEmpty() && p.equals(dynamicVpnPkg.trim())) {
            return true;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("com.follow.clash")
                || lower.equals("com.github.metacubex.clash.meta")
                || lower.equals("com.github.kr328.clash")
                || lower.equals("com.github.kr328.clash.foss")
                || lower.equals("io.nekohasekai.sfa")
                || lower.equals("io.nekohasekai.sagernet")
                || lower.equals("com.v2ray.ang")
                || lower.equals("moe.nb4a")
                || lower.equals("com.getsurfboard")
                || lower.equals("com.wireguard.android")
                || lower.equals("com.tailscale.ipn")
                || lower.equals("app.hiddify.com")
                || lower.equals("com.nebula.karing")
                || lower.equals("com.matsuri.v2")
                || lower.equals("com.cloudflare.onedotonedotonedotone")
                || lower.equals("org.outline.android.client")
                || lower.equals("com.github.shadowsocks")
                || lower.equals("com.v2ray.v2fly")
                || lower.equals("dev.mihomo.app")) {
            return true;
        }
        return lower.contains(".clash")
                || lower.contains(".v2ray")
                || lower.contains(".singbox")
                || lower.contains(".nekobox")
                || lower.contains(".sagernet")
                || lower.contains(".surfboard")
                || lower.contains(".wireguard")
                || lower.contains(".shadowsocks");
    }

    /** Classifies an {@code ip monitor link route} line for event-driven gating. */
    public static int classifyNetlinkLine(String line) {
        if (line == null) {
            return NETLINK_IGNORE;
        }
        String s = line.trim();
        if (s.isEmpty() || s.contains(" lo ") || s.contains(" lo:")
                || s.startsWith("local ") || s.startsWith("broadcast ")
                || s.startsWith("Deleted local ") || s.startsWith("Deleted broadcast ")
                || s.contains("fe80::") || s.contains("ff00::")) {
            return NETLINK_IGNORE;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        boolean mentionsTun = lower.contains(" tun") || lower.contains(" utun")
                || lower.contains(" wg") || lower.contains(" tailscale")
                || lower.contains(" ppp") || lower.contains(" warp")
                || lower.contains(": tun") || lower.contains(": utun")
                || lower.contains(": wg") || lower.contains(": tailscale")
                || lower.contains(": ppp") || lower.contains(": warp");
        if (mentionsTun && (lower.startsWith("deleted ") || lower.contains("state down")
                || lower.contains("no-carrier"))) {
            return NETLINK_TUN_DROP;
        }
        if (mentionsTun || lower.startsWith("default ") || lower.startsWith("deleted default ")
                || lower.contains("dev wlan") || lower.contains("dev rmnet")
                || lower.contains("dev ccmni") || lower.contains("dev eth")
                || lower.contains("wlan0") || lower.contains("rmnet")
                || lower.contains("ccmni") || lower.contains("state up")
                || lower.contains("state down")) {
            return NETLINK_ROUTE_CHANGE;
        }
        return NETLINK_IGNORE;
    }

    /** True when the interface name is a known VPN / WireGuard / Tailscale tunnel device. */
    public static boolean isTunnelInterfaceName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("tun")
                || n.startsWith("utun")
                || n.startsWith("wg")
                || n.startsWith("tailscale")
                || n.startsWith("ppp")
                || n.startsWith("zt")
                || n.startsWith("warp");
    }

    /** Formats the per-target external incident log file path (outside target directories). */
    public static String netEventPath(String targetId) {
        String id = safeId(targetId);
        return LOCK_DIR + "/" + id + ".netevent";
    }

    /** Builds a comma-separated uppercase restricted-ISO list safe for shell config files. */
    public static String restrictedCsv(Collection<String> isos) {
        Set<String> clean = new LinkedHashSet<String>();
        if (isos != null) {
            for (String s : isos) {
                String norm = normalizeIso(s);
                if (!norm.isEmpty()) {
                    clean.add(norm);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String iso : clean) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(iso);
        }
        return sb.toString();
    }

    /**
     * Inserts an idempotent kernel Netfilter UID-level kill-switch rule in custom chain
     * {@link #IPTABLES_CHAIN} for {@code targetPkg} only (blocking TCP/UDP/QUIC/DNS for that UID).
     */
    public static String shellBlockUidCommand(String targetPkg) {
        if (!ShellQuote.packageName(targetPkg)) {
            return "true";
        }
        return "TUID=$(pm list packages -U " + targetPkg + " 2>/dev/null | sed -n 's/.*uid://p' | head -1); "
                + "if [ -n \"$TUID\" ]; then "
                + "for IPT in iptables ip6tables; do "
                + "$IPT -N " + IPTABLES_CHAIN + " 2>/dev/null || true; "
                + "$IPT -C OUTPUT -j " + IPTABLES_CHAIN + " 2>/dev/null || $IPT -I OUTPUT 1 -j " + IPTABLES_CHAIN + " 2>/dev/null || true; "
                + "$IPT -C " + IPTABLES_CHAIN + " -m owner --uid-owner \"$TUID\" -j REJECT 2>/dev/null || "
                + "$IPT -I " + IPTABLES_CHAIN + " 1 -m owner --uid-owner \"$TUID\" -j REJECT 2>/dev/null || true; "
                + "done; fi";
    }

    /** Removes any kernel Netfilter UID block rules for {@code targetPkg}. */
    public static String shellUnblockUidCommand(String targetPkg) {
        if (!ShellQuote.packageName(targetPkg)) {
            return "true";
        }
        return "TUID=$(pm list packages -U " + targetPkg + " 2>/dev/null | sed -n 's/.*uid://p' | head -1); "
                + "if [ -n \"$TUID\" ]; then "
                + "for IPT in iptables ip6tables; do "
                + "while $IPT -D " + IPTABLES_CHAIN + " -m owner --uid-owner \"$TUID\" -j REJECT 2>/dev/null; do :; done; "
                + "done; fi";
    }

    /** Cleanly unhooks and deletes {@link #IPTABLES_CHAIN} from both iptables and ip6tables. */
    public static String shellUnblockAllCommand() {
        return "for IPT in iptables ip6tables; do "
                + "while $IPT -D OUTPUT -j " + IPTABLES_CHAIN + " 2>/dev/null; do :; done; "
                + "$IPT -F " + IPTABLES_CHAIN + " 2>/dev/null || true; "
                + "$IPT -X " + IPTABLES_CHAIN + " 2>/dev/null || true; "
                + "done";
    }

    /**
     * Quick single-shot shell verification used by the Unlock button to check whether proxy &amp;
     * egress country have recovered before clearing the UID kill-switch and process lock.
     */
    public static String shellQuickCheckCommand(String selfPkg) {
        if (!ShellQuote.packageName(selfPkg)) {
            return "echo 'QUICK vpn=0 tproxy=0 ip= cc='";
        }
        return "VPN=0; "
                + "for d in /sys/class/net/*; do n=\"${d##*/}\"; "
                + "case \"$n\" in tun*|utun*|wg*|tailscale*|ppp*|warp*) "
                + "ip link show dev \"$n\" 2>/dev/null | grep -q 'UP' && VPN=1 && break ;; esac; done; "
                + "TP=0; "
                + "if iptables -t nat -S 2>/dev/null | grep -qE -- '-j (REDIRECT|TPROXY)( |$)' || "
                + "iptables -t mangle -S 2>/dev/null | grep -qE -- '-j TPROXY( |$)'; then TP=1; fi; "
                + "APK=$(pm path " + selfPkg + " 2>/dev/null | cut -d: -f2); "
                + "EG=''; "
                + "if [ -n \"$APK\" ]; then "
                + "EG=$(app_process -Djava.class.path=$APK /system/bin com.walter.claudewilson.Bridge egress 2>/dev/null | grep '^EGRESS ' | head -1); "
                + "fi; "
                + "echo \"QUICK vpn=$VPN tproxy=$TP $EG\"";
    }

    /**
     * Stops the persistent {@code Bridge guard} daemon (and any legacy {@code ip-guard.sh}),
     * cleans up all child {@code ip monitor} / {@code logcat} processes, removes config files,
     * and clears any active {@link #IPTABLES_CHAIN} rules cleanly.
     */
    public static String shellStopCommand() {
        return "rm -f " + CONF_PATH + " " + SCRIPT_PATH + "; "
                + "if [ -f " + PID_PATH + " ]; then "
                + "  P=$(cat " + PID_PATH + " 2>/dev/null); "
                + "  if [ -n \"$P\" ]; then "
                + "    for C in $(ps -ef 2>/dev/null | awk -v p=\"$P\" '$3==p{print $2}'); do "
                + "      pkill -9 -P \"$C\" 2>/dev/null; kill -9 \"$C\" 2>/dev/null; "
                + "    done; "
                + "    pkill -9 -P \"$P\" 2>/dev/null; kill -9 \"$P\" 2>/dev/null; "
                + "  fi; "
                + "  rm -f " + PID_PATH + "; "
                + "fi; "
                + "for P in $(ps -ef 2>/dev/null | grep -E 'claudewilson\\.Bridge guard|ip-guard\\.sh|ip monitor link route|wm_set_resumed_activity' | grep -v grep | awk '{print $2}'); do "
                + "  pkill -9 -P \"$P\" 2>/dev/null; kill -9 \"$P\" 2>/dev/null; "
                + "done; "
                + shellUnblockAllCommand() + "; echo ok";
    }

    /**
     * Full fail-open cleanup executed only when ClaudeWilson itself is uninstalled while the
     * headless daemon is running:
     * <ul>
     *   <li>Clears all {@code cw_ipg_*} UID iptables block rules.</li>
     *   <li>Removes persisted {@code .lock}/{@code .lockreason} files and writes fresh
     *       {@code .unlock} timestamps so {@code SystemBodyguardHooks} inside {@code system_server}
     *       immediately releases both disk and in-memory locks without requiring a reboot.</li>
     *   <li>Disarms standalone {@code guard.mode} to {@code OFF} and removes {@code ip-guard.conf}.</li>
     *   <li>Stops IPv6 watchdogs and restores kernel IPv6 via {@link Ipv6Policy#shellRestoreCommand}.</li>
     * </ul>
     */
    public static String shellUninstallCleanupCommand(Collection<String> targetIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(shellUnblockAllCommand()).append("; ");
        sb.append("mkdir -p ").append(DIR).append(" ").append(LOCK_DIR).append("; ");
        sb.append("NOW=$(date +%s 2>/dev/null); [ -n \"$NOW\" ] && NOW=\"${NOW}000\" || NOW=9999999999999; ");
        if (targetIds != null) {
            Set<String> seen = new LinkedHashSet<String>();
            for (String raw : targetIds) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String tid = safeId(raw);
                if (seen.add(tid)) {
                    sb.append("rm -f ").append(LOCK_DIR).append('/').append(tid).append(".lock ")
                            .append(LOCK_DIR).append('/').append(tid).append(".lockreason; ")
                            .append("printf '%s' \"$NOW\" > ").append(LOCK_DIR).append('/')
                            .append(tid).append(".unlock && chmod 644 ").append(LOCK_DIR)
                            .append('/').append(tid).append(".unlock; ");
                }
            }
        }
        sb.append("printf '%s' '").append(BodyguardPolicy.MODE_OFF).append("' > ")
                .append(DIR).append("/guard.mode && chmod 644 ").append(DIR).append("/guard.mode; ");
        sb.append("rm -f ").append(CONF_PATH).append(' ').append(PID_PATH).append("; ");
        sb.append(Ipv6Policy.shellRestoreCommand());
        return sb.toString();
    }

    /**
     * Writes {@link #CONF_PATH} atomically and ensures the single-instance persistent root Java
     * daemon ({@code Bridge guard}) is running on the current APK. When already running on the
     * same APK, the daemon hot-reloads {@link #CONF_PATH} without restarting.
     */
    public static String shellSyncCommand(String guardMode, Config cfg,
                                          String selfPkg, String targetId, String targetPkg,
                                          String targetLabel, String expectedIso,
                                          Collection<String> restrictedIsos) {
        String mode = BodyguardPolicy.normalizeMode(guardMode);
        Config c = cfg == null ? Config.defaults() : cfg;
        String exp = normalizeIso(expectedIso);
        if (BodyguardPolicy.MODE_OFF.equals(mode) || !c.anyEnabled()
                || exp.isEmpty() || !ShellQuote.packageName(selfPkg)
                || !ShellQuote.packageName(targetPkg)) {
            return shellStopCommand();
        }
        String tid = safeId(targetId);
        String label = safeAsciiLabel(targetLabel, tid);
        String restCsv = restrictedCsv(restrictedIsos);
        int kDrop = c.killOnDrop ? 1 : 0;
        int kRest = c.killOnRestricted ? 1 : 0;
        int pollMin = (c.pollEveryMin && c.needsCountryCheck()) ? 1 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(DIR).append(" ").append(LOCK_DIR)
                .append(" && chmod 755 ").append(DIR).append(" ").append(LOCK_DIR).append("; ");
        sb.append("APK=$(pm path ").append(selfPkg).append(" 2>/dev/null | cut -d: -f2 | head -1); ");
        sb.append("EXP_TUN=0; EXP_PROXY=0; ")
                .append("for d in /sys/class/net/*; do n=\"${d##*/}\"; ")
                .append("case \"$n\" in tun*|utun*|wg*|tailscale*|ppp*|warp*) ")
                .append("ip link show dev \"$n\" 2>/dev/null | grep -q 'UP' && EXP_TUN=1 && EXP_PROXY=1 && break ;; esac; done; ")
                .append("if [ \"$EXP_PROXY\" = \"0\" ]; then ")
                .append("iptables -t nat -S 2>/dev/null | grep -qE -- '-j (REDIRECT|TPROXY)( |$)' && EXP_PROXY=1; ")
                .append("iptables -t mangle -S 2>/dev/null | grep -qE -- '-j TPROXY( |$)' && EXP_PROXY=1; ")
                .append("fi; ");
        sb.append("cat > ").append(CONF_PATH).append(".tmp << CW_IPCONF_EOF\n")
                .append("MODE=").append(mode).append('\n')
                .append("SELF_PKG=").append(selfPkg).append('\n')
                .append("APK_PATH=$APK\n")
                .append("TARGET_ID=").append(tid).append('\n')
                .append("TARGET_PKG=").append(targetPkg).append('\n')
                .append("TARGET_LABEL=").append(label).append('\n')
                .append("EXPECTED_ISO=").append(exp).append('\n')
                .append("KILL_ON_DROP=").append(kDrop).append('\n')
                .append("KILL_ON_RESTRICTED=").append(kRest).append('\n')
                .append("DRIFT_POLICY=").append(c.driftPolicy).append('\n')
                .append("POLL_MIN=").append(pollMin).append('\n')
                .append("RESTRICTED_CSV=").append(restCsv).append('\n')
                .append("EXPECT_TUN=$EXP_TUN\n")
                .append("EXPECT_PROXY=$EXP_PROXY\n")
                .append("CW_IPCONF_EOF\n");
        sb.append("chmod 644 ").append(CONF_PATH).append(".tmp && mv -f ")
                .append(CONF_PATH).append(".tmp ").append(CONF_PATH).append("; ");
        // Retire any legacy ip-guard.sh daemon from prior APK versions.
        sb.append("rm -f ").append(SCRIPT_PATH).append("; ")
                .append("for LP in $(ps -ef 2>/dev/null | grep 'ip-guard\\.sh' | grep -v grep | awk '{print $2}'); do ")
                .append("  for LC in $(ps -ef 2>/dev/null | awk -v p=\"$LP\" '$3==p{print $2}'); do kill -9 \"$LC\" 2>/dev/null; done; ")
                .append("  kill -9 \"$LP\" 2>/dev/null; ")
                .append("done; ");
        // Keep the single persistent Bridge guard process if it is alive on the exact current APK.
        sb.append("KEEP=''; ")
                .append("if [ -f ").append(PID_PATH).append(" ]; then ")
                .append("  P=$(cat ").append(PID_PATH).append(" 2>/dev/null); ")
                .append("  if [ -n \"$P\" ] && kill -0 \"$P\" 2>/dev/null ")
                .append("    && grep -qa 'com.walter.claudewilson.Bridge' \"/proc/$P/cmdline\" 2>/dev/null ")
                .append("    && [ -n \"$APK\" ] && grep -qa \"$APK\" \"/proc/$P/cmdline\" 2>/dev/null; then ")
                .append("    KEEP=\"$P\"; ")
                .append("  fi; ")
                .append("fi; ")
                .append("for P in $(ps -ef 2>/dev/null | grep 'claudewilson\\.Bridge guard' | grep -v grep | awk '{print $2}'); do ")
                .append("  if [ -z \"$KEEP\" ] && [ -n \"$APK\" ] && grep -qa \"$APK\" \"/proc/$P/cmdline\" 2>/dev/null; then ")
                .append("    KEEP=\"$P\"; echo \"$P\" > ").append(PID_PATH).append("; ")
                .append("  elif [ \"$P\" != \"$KEEP\" ]; then ")
                .append("    for C in $(ps -ef 2>/dev/null | awk -v p=\"$P\" '$3==p{print $2}'); do kill -9 \"$C\" 2>/dev/null; done; ")
                .append("    kill -9 \"$P\" 2>/dev/null; ")
                .append("  fi; ")
                .append("done; ")
                .append("for OP in $(ps -ef 2>/dev/null | grep -E 'ip monitor link route|wm_set_resumed_activity' | grep -v grep | awk '$3==1{print $2}'); do ")
                .append("  kill -9 \"$OP\" 2>/dev/null; ")
                .append("done; ")
                .append("if [ -z \"$KEEP\" ] && [ -n \"$APK\" ]; then ")
                .append("  nohup app_process -Djava.class.path=\"$APK\" /system/bin com.walter.claudewilson.Bridge guard </dev/null >/dev/null 2>&1 & ")
                .append("fi; ")
                .append("echo ok");
        return sb.toString();
    }

    private static boolean isInSet(Set<String> set, String iso) {
        if (set == null || set.isEmpty() || iso.isEmpty()) {
            return false;
        }
        return set.contains(iso) || set.contains(iso.toLowerCase(Locale.ROOT));
    }

    private static String safeId(String id) {
        if (id == null) {
            return "target";
        }
        String s = id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return s.isEmpty() ? "target" : s;
    }

    private static String safeAsciiLabel(String label, String fallback) {
        if (label == null) {
            return fallback;
        }
        String s = label.trim().replaceAll("[^A-Za-z0-9 _.-]", "");
        return s.isEmpty() ? fallback : s;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
