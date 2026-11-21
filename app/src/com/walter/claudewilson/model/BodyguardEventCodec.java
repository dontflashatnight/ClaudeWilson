package com.walter.claudewilson.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure codec and signal-classification rules for runtime Bodyguard events.
 *
 * <p>Encoded payloads stay strictly within Android's 128-byte limit for
 * {@code ActivityManager.setProcessStateSummary(byte[])}, allowing zero-file-residue incident
 * reporting from the target process back to ClaudeWilson via read-only system exit-info queries.
 */
public final class BodyguardEventCodec {

    public static final String MAGIC = "CWBG1";
    public static final int MAX_BYTES = 128;
    private static final byte[] MAGIC_PREFIX =
            (MAGIC + "|").getBytes(StandardCharsets.US_ASCII);

    private BodyguardEventCodec() {
    }

    /** True when raw {@code ApplicationExitInfo.getProcessStateSummary()} bytes belong to Bodyguard. */
    public static boolean isBodyguardSummary(byte[] summary) {
        if (summary == null || summary.length < MAGIC_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < MAGIC_PREFIX.length; i++) {
            if (summary[i] != MAGIC_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    public static final class Event {
        public final String action;
        public final String gate;
        public final String signalKey;
        public final String callerShort;
        public final String version;

        public Event(String action, String gate, String signalKey,
                     String callerShort, String version) {
            this.action = nn(action);
            this.gate = nn(gate);
            this.signalKey = nn(signalKey);
            this.callerShort = nn(callerShort);
            this.version = nn(version);
        }
    }

    /** Encodes an incident frame into UTF-8 bytes guaranteed to be {@code <= 128} bytes. */
    public static byte[] encode(String action, String gate, String signalKey,
                                String callerShort, String version) {
        String a = cleanToken(action, 10);
        String g = cleanToken(gate, 16);
        String k = cleanToken(signalKey, 48);
        String c = cleanToken(callerShort, 28);
        String v = cleanToken(version, 12);
        String frame = MAGIC + "|" + a + "|" + g + "|" + k + "|" + c + "|" + v;
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return bytes;
        }
        // Trim caller first, then key, until strictly <= 128 bytes.
        c = cleanToken(c, 14);
        frame = MAGIC + "|" + a + "|" + g + "|" + k + "|" + c + "|" + v;
        bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return bytes;
        }
        k = cleanToken(k, 28);
        frame = MAGIC + "|" + a + "|" + g + "|" + k + "|" + c + "|" + v;
        bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return bytes;
        }
        byte[] clipped = new byte[MAX_BYTES];
        System.arraycopy(bytes, 0, clipped, 0, MAX_BYTES);
        return clipped;
    }

    /**
     * Decodes a {@code CWBG1|...} frame from either plain ASCII/UTF-8 or hex encoding
     * (Android {@code dumpsys activity exit-info} may print {@code state=} as hex or string).
     */
    public static Event decode(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String decoded = null;
        int magicAt = s.indexOf(MAGIC + "|");
        if (magicAt >= 0) {
            decoded = s.substring(magicAt);
        } else if (isHex(s) && s.length() >= 12 && (s.length() % 2) == 0) {
            String fromHex = hexToUtf8(s);
            if (fromHex != null && fromHex.startsWith(MAGIC + "|")) {
                decoded = fromHex;
            }
        }
        if (decoded == null) {
            return null;
        }
        int nl = decoded.indexOf('\n');
        if (nl >= 0) {
            decoded = decoded.substring(0, nl);
        }
        String[] parts = decoded.split("\\|", -1);
        if (parts.length < 4 || !MAGIC.equals(parts[0])) {
            return null;
        }
        String action = parts[1].trim();
        String gate = parts[2].trim();
        String key = parts[3].trim();
        String caller = parts.length > 4 ? parts[4].trim() : "";
        String ver = parts.length > 5 ? parts[5].trim() : "";
        if (gate.isEmpty() || key.isEmpty()) {
            return null;
        }
        return new Event(action, gate, key, caller, ver);
    }

    /**
     * True when {@code className} belongs to Android/Java/Xposed platform internals rather than
     * target application or embedded third-party risk/telemetry SDK code.
     */
    public static boolean isFrameworkFrame(String className) {
        if (className == null || className.isEmpty()) {
            return true;
        }
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("dalvik.")
                || className.startsWith("libcore.")
                || className.startsWith("jdk.")
                || className.startsWith("android.")
                || className.startsWith("com.android.")
                || className.startsWith("androidx.")
                || className.startsWith("kotlin.")
                || className.startsWith("kotlinx.")
                || className.startsWith("de.robv.android.xposed.")
                || className.startsWith("io.github.libxposed.")
                || className.startsWith("org.matrix.vector.")
                || className.startsWith("org.lsposed.")
                || className.startsWith("io.github.lsposed.")
                || className.startsWith("Vector_")
                || className.startsWith("LSPHooker")
                || className.startsWith("EdHooker_")
                || className.startsWith("me.weishu.epic.")
                || className.startsWith("com.walter.claudewilson.");
    }

    /** Compact caller label (e.g. {@code c.s.a.DeviceInfo.read}) for the 128B summary frame. */
    public static String abbreviateCaller(String className, String methodName) {
        String c = nn(className).trim();
        String m = nn(methodName).trim();
        if (c.isEmpty()) {
            return m;
        }
        String[] parts = c.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0)).append('.');
            }
        }
        sb.append(parts[parts.length - 1]);
        if (!m.isEmpty()) {
            sb.append('.').append(m);
        }
        return cleanToken(sb.toString(), 28);
    }

    /** True for high-risk identity/radio/hardware SystemProperties keys guarded by SYS_PROP gate. */
    public static boolean isSensitivePropKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        return k.startsWith("ro.serial")
                || k.startsWith("ro.boot.serial")
                || k.startsWith("ro.ril.")
                || k.startsWith("ril.")
                || k.startsWith("persist.radio.")
                || k.startsWith("gsm.")
                || k.startsWith("ro.oem.")
                || k.startsWith("ro.soc.")
                || k.startsWith("ro.hardware")
                || k.startsWith("ro.csc.")
                || k.startsWith("ro.build.fingerprint")
                || k.startsWith("ro.product.model")
                || k.startsWith("ro.product.manufacturer")
                || k.startsWith("ro.product.brand")
                || k.startsWith("ro.product.device")
                || k.startsWith("ro.boot.hardware.sku")
                || k.equals("persist.sys.timezone");
    }

    /** True for high-risk Settings keys guarded by SETTINGS_KEY gate. */
    public static boolean isSensitiveSettingsKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        // Ignore common benign UI/accessibility/IME settings that contain "id" or "sim".
        if ("default_input_method".equals(k)
                || "enabled_input_methods".equals(k)
                || "selected_input_method_subtype".equals(k)
                || "accessibility_enabled".equals(k)
                || "enabled_accessibility_services".equals(k)
                || "user_setup_complete".equals(k)
                || "device_provisioned".equals(k)
                || "auto_time".equals(k)
                || "auto_time_zone".equals(k)
                || "time_12_24".equals(k)) {
            return false;
        }
        return k.contains("imei")
                || k.contains("meid")
                || k.contains("serial")
                || k.contains("mac_address")
                || k.contains("bluetooth_address")
                || k.contains("wifi_mac")
                || k.contains("oaid")
                || k.contains("gaid")
                || k.contains("advertising_id")
                || k.contains("udid")
                || k.equals("android_id")
                || k.equals("device_name")
                || k.equals("bluetooth_name")
                || k.equals("mock_location")
                || k.startsWith("adb_")
                || k.equals("development_settings_enabled");
    }

    /**
     * True for sensitive Settings keys already spoofed/handled by ClaudeWilson's own hooks
     * (IdentityHooks, BleHooks, LocationHooks), so Bodyguard BLOCK_KILL never false-kills on them.
     */
    public static boolean isBuiltinAllowedSettingsKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        return "android_id".equals(k)
                || "adb_enabled".equals(k)
                || "development_settings_enabled".equals(k)
                || "adb_wifi_enabled".equals(k)
                || "mock_location".equals(k)
                || "device_name".equals(k)
                || "bluetooth_name".equals(k);
    }

    /**
     * True for standard Google Play Services module loaders (Dynamite / Chimera) that legitimately
     * load DEX payloads in-process; target app and third-party risk SDK callers return false.
     */
    public static boolean isTrustedDynamicLoaderCaller(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        return className.startsWith("com.google.android.gms.dynamite.")
                || className.startsWith("com.google.android.gms.chimera.");
    }

    /** True for high-risk hardware/network paths guarded by SENSITIVE_PATH gate. */
    public static boolean isSensitivePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        String p = path.trim();
        if (p.startsWith("/proc/")) {
            return "/proc/net/arp".equals(p);
        }
        if (!p.startsWith("/sys/")) {
            return false;
        }
        return (p.startsWith("/sys/class/net/") && p.endsWith("/address"))
                || (p.startsWith("/sys/block/") && p.endsWith("/cid"))
                || p.startsWith("/sys/devices/virtual/dmi/")
                || "/sys/devices/soc0/serial_number".equals(p)
                || "/sys/class/android_usb/android0/iSerial".equals(p);
    }

    /** Normalizes a raw hostname (lowercase, strips brackets, trailing dot, and optional :port). */
    public static String normalizeHost(String rawHost) {
        if (rawHost == null) {
            return "";
        }
        String h = rawHost.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) {
            return "";
        }
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close > 1) {
                h = h.substring(1, close);
            }
        } else {
            int firstColon = h.indexOf(':');
            if (firstColon > 0 && h.indexOf(':', firstColon + 1) < 0) {
                h = h.substring(0, firstColon);
            }
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /**
     * Extracts the normalized host from an {@code http/https/ws/wss} URL string, or {@code ""}
     * for non-network schemes (e.g. {@code file:}, {@code jar:}, {@code content:}).
     */
    public static String extractHttpUrlHost(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return "";
        }
        int sep = rawUrl.indexOf("://");
        if (sep <= 0) {
            return "";
        }
        String scheme = rawUrl.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)
                && !"ws".equals(scheme) && !"wss".equals(scheme)) {
            return "";
        }
        int start = sep + 3;
        int end = rawUrl.length();
        for (int i = start; i < rawUrl.length(); i++) {
            char c = rawUrl.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rawUrl.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        return normalizeHost(authority);
    }

    /**
     * Rewrites the {@code (Linux; Android <ver>; <model>...)} token inside a WebView/Chromium
     * User-Agent string when the active persona overrides Android release or device model.
     */
    public static String rewriteUserAgent(String ua, String androidRelease, String model) {
        if (ua == null || ua.isEmpty()) {
            return ua;
        }
        String rel = androidRelease == null ? "" : androidRelease.trim();
        String mod = model == null ? "" : model.trim();
        if (rel.isEmpty() && mod.isEmpty()) {
            return ua;
        }
        int marker = ua.indexOf("Android ");
        if (marker < 0) {
            return ua;
        }
        int closeParen = ua.indexOf(')', marker);
        if (closeParen < 0) {
            return ua;
        }
        String segment = ua.substring(marker, closeParen);
        int semi = segment.indexOf(';');
        if (semi < 0) {
            return ua;
        }
        String curVer = segment.substring("Android ".length(), semi).trim();
        String curTail = segment.substring(semi + 1).trim();
        String newVer = rel.isEmpty() ? curVer : rel;
        String newTail;
        if (mod.isEmpty() || "K".equals(curTail)) {
            // Preserve Chromium User-Agent Reduction token "K" if already reduced.
            newTail = curTail;
        } else {
            int buildIdx = curTail.indexOf(" Build/");
            newTail = buildIdx >= 0 ? mod + curTail.substring(buildIdx) : mod;
        }
        return ua.substring(0, marker) + "Android " + newVer + "; " + newTail
                + ua.substring(closeParen);
    }

    /**
     * True for empty, loopback, local/virtual WebView hosts, or IP literals (including TUN
     * Fake-IP ranges such as 198.18.0.0/15 resolved after DNS lookup).
     */
    public static boolean isLocalOrNonEgressHost(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isEmpty()) {
            return true;
        }
        if ("localhost".equals(normalizedHost)
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local")
                || normalizedHost.endsWith(".invalid")
                || "androidplatform.net".equals(normalizedHost)
                || normalizedHost.endsWith(".androidplatform.net")) {
            return true;
        }
        if (normalizedHost.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        return isIpv4Literal(normalizedHost);
    }

    /**
     * True when {@code rawHost} is local/non-egress or matches any audited domain suffix in
     * {@code allowHosts} or any user-whitelisted {@code host:...} entry in {@code allowCustom}.
     */
    public static boolean isHostAllowed(String rawHost,
                                        java.util.Set<String> allowHosts,
                                        java.util.Set<String> allowCustom) {
        String host = normalizeHost(rawHost);
        if (isLocalOrNonEgressHost(host)) {
            return true;
        }
        if (allowHosts != null) {
            for (String allowed : allowHosts) {
                if (matchesDomain(host, allowed)) {
                    return true;
                }
            }
        }
        if (allowCustom != null) {
            for (String entry : allowCustom) {
                if (entry != null && entry.startsWith("host:")
                        && matchesDomain(host, entry.substring(5))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesDomain(String normalizedHost, String rawAllowed) {
        if (rawAllowed == null || rawAllowed.isEmpty()) {
            return false;
        }
        String a = normalizeHost(rawAllowed);
        if (a.startsWith("*.")) {
            a = a.substring(2);
        }
        while (a.startsWith(".")) {
            a = a.substring(1);
        }
        if (a.isEmpty()) {
            return false;
        }
        return normalizedHost.equals(a) || normalizedHost.endsWith("." + a);
    }

    private static boolean isIpv4Literal(String s) {
        int dots = 0;
        int partLen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (partLen == 0) {
                    return false;
                }
                dots++;
                partLen = 0;
            } else if (c >= '0' && c <= '9') {
                partLen++;
                if (partLen > 3) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return dots == 3 && partLen > 0;
    }

    private static String cleanToken(String raw, int maxChars) {
        String s = nn(raw).replace('|', '_').replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > maxChars) {
            return s.substring(0, maxChars);
        }
        return s;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String hexToUtf8(String hex) {
        try {
            int len = hex.length();
            byte[] out = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                int hi = Character.digit(hex.charAt(i), 16);
                int lo = Character.digit(hex.charAt(i + 1), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out[i / 2] = (byte) ((hi << 4) | lo);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
