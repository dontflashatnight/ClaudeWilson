package com.walter.claudewilson.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java HMA-grade target-process package visibility policy (zero Android imports, zero CJK).
 *
 * <p>Models modern Hide My Applist (HMA / HMAL) semantics:
 * <ul>
 *   <li>{@link #MODE_ALLOWLIST}: target sees ONLY essential system/GMS/WebView/Chrome dependencies,
 *       itself, optional non-root system apps (when {@code excludeSystemApps} is true and not in
 *       {@code extraHidden}), and the user-chosen allowlisted packages.</li>
 *   <li>{@link #MODE_DENYLIST}: target sees all packages except always-hidden root/hook/hide
 *       artifacts, {@code extraHidden}, and the user-chosen hidden packages.</li>
 *   <li>{@link #MODE_DEFAULT}: hides always-hidden root/hook/control packages and any explicit
 *       {@code extraHidden} packages.</li>
 * </ul>
 *
 * <p>Also supplies pure-Java HMA preset category classifiers (Root Managers, Detectors, Custom ROM
 * packages, Suspicious packages), path extraction, {@code pm} command detection, and
 * {@code Settings.Secure} IME string sanitization.
 */
public final class AppVisibilityPolicy {
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_ALLOWLIST = "allowlist";
    public static final String MODE_DENYLIST = "denylist";

    public static final int CAT_NONE = 0;
    public static final int CAT_ROOT = 1;
    public static final int CAT_PROXY = 2;
    public static final int CAT_DOMESTIC = 3;
    public static final int CAT_DEV = 4;

    public static final String FALLBACK_IME =
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";

    private static final Set<String> ESSENTIAL_EXACT = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "android",
                    "com.android.systemui",
                    "com.android.settings",
                    "com.android.vending",
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.google.android.webview",
                    "com.android.webview",
                    "com.android.chrome",
                    "com.google.android.packageinstaller",
                    "com.android.packageinstaller",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller",
                    "com.android.documentsui",
                    "com.google.android.documentsui",
                    "com.android.keychain",
                    "com.android.certinstaller",
                    "com.google.android.inputmethod.latin",
                    "com.android.inputmethod.latin",
                    "com.google.android.tts",
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.captiveportallogin",
                    "com.android.captiveportallogin",
                    "com.android.phone",
                    "com.android.shell"
            )));

    private static final String[] ESSENTIAL_PREFIXES = {
            "com.android.providers.",
            "com.google.android.providers.",
            "com.google.android.trichromelibrary",
            "com.google.android.ext.",
            "com.android.internal.",
            "com.google.android.networkstack",
            "com.android.networkstack",
            "com.google.android.modulemetadata",
            "com.android.modulemetadata",
    };

    /** Modern root, hook, and hide-module managers hidden in every mode alongside RootArtifactPolicy. */
    private static final Set<String> FORCED_HIDDEN_EXACT = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "com.tsng.hidemyapplist",
                    "com.google.android.hmal",
                    "io.github.vvb2060.magisk",
                    "io.github.huskydg.magisk",
                    "com.rifsxd.ksunext",
                    "com.sukisu.ultra",
                    "io.github.a13e300.ksuwebui",
                    "com.dergoogler.mmrl",
                    "org.lsposed.lspatch",
                    "io.github.jingmatrix.lsposed",
                    "com.privacymask.xposed",
                    "io.github.imkamaran.hidemydevice",
                    "com.hidemydevice",
                    "com.hide.my.device",
                    "local.settings.entryhider"
            )));

    private static final String[] FORCED_HIDDEN_TOKENS = {
            "hidemyapplist", "lsposed", "kernelsu", "ksunext", "sukisu", "apatch", "magisk",
            "xposed", "rootcloak", "hideroot", "hidemyroot", "shamiko", "zygisk"
    };

    private static final String[] ROOT_MANAGER_EXACT = {
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "io.github.huskydg.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "com.sukisu.ultra",
            "me.bmax.apatch",
            "io.github.a13e300.ksuwebui",
            "com.dergoogler.mmrl",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su"
    };

    private static final String[] ROOT_MANAGER_TOKENS = {
            "magisk", "kernelsu", "ksunext", "sukisu", "apatch", "supersu", "superuser",
            "ksuwebui", "mmrl", "shamiko"
    };

    private static final String[] DETECTOR_TOKENS = {
            "applistdetector", "io.github.vvb2060.mahoshojo", "momo", "nativedetector",
            "native.test", "io.github.vvb2060.keyattestation", "keyattestation",
            "playintegrity", "gr.nikolasspyr.integritycheck", "yasnac", "tbchecker",
            "rootbeer", "rootchecker", "com.scottyab.rootbeer", "com.joeykrim.rootcheck",
            "holmes", "com.byxiaorun.detector", "com.zhenxi.hunter", "ruru",
            "devcheck", "deviceinfo", "aida64", "cpuz", "safetynet", "lunadetector",
            "spic", "com.henrikherzig.playintegritychecker"
    };

    private static final String[] CUSTOM_ROM_PREFIXES = {
            "org.lineageos.",
            "com.crdroid.",
            "org.pixelexperience.",
            "org.evolution.",
            "org.derpfest.",
            "co.aospa.",
            "org.omnirom.",
            "org.arrowos.",
            "org.calyxos.",
            "org.grapheneos.",
            "com.havoc.",
            "com.blissroms.",
            "com.nameless.",
            "org.cherishos.",
            "org.yaap.",
            "org.matrixx.",
            "com.rising.",
            "org.pixelos.",
            "com.statixos.",
            "eu.xiaomi.",
            "lineageos.",
            "cyanogenmod.",
            "org.cyanogenmod."
    };

    private static final String[] PROXY_TOKENS = {
            "clash", "v2ray", "singbox", "sing_box", "sing.box", "sing-box",
            "nekobox", "nekoray", "nekohasekai", "moe.nb4a", "flclash", "clashbox",
            "mihomo", "shadowsocks", "shadowrocket", "surfboard", "kitsunebi", "hiddify",
            "v2box", "sagernet", "matsuri", "karing", "wireguard", "tailscale", "tun2socks",
            "trojan", "naiveproxy", "xray", "outline.android", "clashmeta", "exclave",
            "netproxy", "hysteria", "amnezia", "zerotier", "adguard", "happproxy",
            "cloudflare.onedotonedotonedotone", "box4", "daed", "streisand", "bepass",
            "oblivion", "psiphon", "orbot", "torproject", "openvpn", "strongswan",
            "mullvad", "protonvpn", "nordvpn", "expressvpn", "surfshark", "windscribe",
            "juicity", "mieru"
    };

    private static final String[] DOMESTIC_TOKENS = {
            "tencent", "wechat", "alipay", "eg.android.alipaygphone", "taobao", "tmall",
            "baidu", "bytedance", "ss.android.ugc", "douyin", "weibo", "dingtalk",
            "meituan", "jingdong", "xunmeng.pinduoduo", "zhihu", "bilibili", "xingin.xhs",
            "netease", "kuaishou", "didiglobal", "autonavi", "amap", "quark", "ucmobile",
            "xiaomi.market", "xiaomi.mipicks", "huawei.appmarket", "heytap", "coolapk",
            "dragon.read", "dianping", "ctrip", "unionpay", "icbc", "chinamworld", "cmbchina"
    };

    private static final String[] DEV_TOKENS = {
            "termux", "bin.mt", "npmanager", "chelpus", "luckypatcher", "fakegps",
            "mocklocation", "virtualxposed", "virtualapp", "vmos", "vphonegaga",
            "parallel", "island", "shelter", "hail", "icebox",
            "frida", "httptoolkit", "httpcanary", "reqable", "charles", "mitmproxy",
            "appmanager", "libchecker", "bluelineconsole", "shizuku"
    };

    private AppVisibilityPolicy() {
    }

    public static String normalizeMode(String mode) {
        if (MODE_ALLOWLIST.equalsIgnoreCase(mode)) {
            return MODE_ALLOWLIST;
        }
        if (MODE_DENYLIST.equalsIgnoreCase(mode)) {
            return MODE_DENYLIST;
        }
        return MODE_DEFAULT;
    }

    /** True for this control app, root managers, Xposed/LSPosed/HMA managers — ALWAYS hidden. */
    public static boolean isForcedHidden(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (RootArtifactPolicy.hiddenPackage(pkg) || FORCED_HIDDEN_EXACT.contains(pkg)) {
            return true;
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String tok : FORCED_HIDDEN_TOKENS) {
            if (lower.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    /** HMA preset classifier: Root managers (Magisk / KernelSU / KSU-Next / SukiSU / APatch / SuperSU). */
    public static boolean isRootManagerPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String exact : ROOT_MANAGER_EXACT) {
            if (exact.equals(pkg)) return true;
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String tok : ROOT_MANAGER_TOKENS) {
            if (lower.contains(tok)) return true;
        }
        return false;
    }

    /** HMA preset classifier: Environment / Root / Applist / Integrity detector apps. */
    public static boolean isDetectorPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String tok : DETECTOR_TOKENS) {
            if (lower.contains(tok)) return true;
        }
        return false;
    }

    /** HMA preset classifier: Custom ROM packages (LineageOS, crDroid, EvolutionX, etc.). */
    public static boolean isCustomRomPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String pfx : CUSTOM_ROM_PREFIXES) {
            if (lower.startsWith(pfx)) return true;
        }
        return false;
    }

    /** HMA preset classifier: LSPosed / Xposed manager packages (complements manifest meta-data scan). */
    public static boolean isXposedManagerPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(Locale.ROOT);
        return lower.contains("lsposed") || lower.contains("xposed")
                || lower.contains("lspatch") || lower.contains("edxposed")
                || lower.contains("hidemyapplist") || "com.google.android.hmal".equals(pkg);
    }

    /** HMA preset classifier: Suspicious apps (proxy/VPN, reverse/mod tools, domestic high-signal). */
    public static boolean isSuspiciousPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (isRootManagerPackage(pkg) || isDetectorPackage(pkg) || isCustomRomPackage(pkg)) {
            return false;
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String tok : PROXY_TOKENS) {
            if (lower.contains(tok)) return true;
        }
        for (String tok : DOMESTIC_TOKENS) {
            if (lower.contains(tok)) return true;
        }
        for (String tok : DEV_TOKENS) {
            if (lower.contains(tok)) return true;
        }
        return false;
    }

    /**
     * Packages that must remain visible to prevent target crashes, broken OAuth Custom Tabs,
     * WebView failures, or Play Billing errors.
     */
    public static boolean isEssentialPackage(String pkg, String targetPkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (targetPkg != null && !targetPkg.isEmpty() && targetPkg.equals(pkg)) {
            return true;
        }
        if (ESSENTIAL_EXACT.contains(pkg)) {
            return true;
        }
        for (String pfx : ESSENTIAL_PREFIXES) {
            if (pkg.startsWith(pfx)) {
                return true;
            }
        }
        return false;
    }

    /** Classifies a package into {@code CAT_ROOT / CAT_PROXY / CAT_DOMESTIC / CAT_DEV / CAT_NONE}. */
    public static int categoryOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return CAT_NONE;
        }
        if (isForcedHidden(pkg)) {
            return CAT_ROOT;
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String tok : PROXY_TOKENS) {
            if (lower.contains(tok)) {
                return CAT_PROXY;
            }
        }
        for (String tok : DOMESTIC_TOKENS) {
            if (lower.contains(tok)) {
                return CAT_DOMESTIC;
            }
        }
        if (isDetectorPackage(pkg)) {
            return CAT_DEV;
        }
        for (String tok : DEV_TOKENS) {
            if (lower.contains(tok)) {
                return CAT_DEV;
            }
        }
        return CAT_NONE;
    }

    /** Heuristic tag for proxy/VPN, domestic-region, root, or reverse-engineering packages. */
    public static boolean isSensitivePackage(String pkg) {
        return categoryOf(pkg) != CAT_NONE;
    }

    /** Legacy 4-arg evaluator treating {@code isSystemApp=false, excludeSystemApps=false}. */
    public static boolean isHidden(String mode, Set<String> selected,
                                   String pkg, String targetPkg) {
        return isHidden(mode, selected, null, false, pkg, false, targetPkg);
    }

    /** 6-arg evaluator without {@code extraHidden}. */
    public static boolean isHidden(String mode, Set<String> selected, boolean excludeSystemApps,
                                   String pkg, boolean isSystemApp, String targetPkg) {
        return isHidden(mode, selected, null, excludeSystemApps, pkg, isSystemApp, targetPkg);
    }

    /**
     * Full HMA visibility evaluator with explicit {@code extraHidden} force-hide override set.
     *
     * <p>Invariant 1: {@link #isForcedHidden(String)} is ALWAYS hidden.
     * <p>Invariant 2: {@link #isEssentialPackage(String, String)} is ALWAYS visible.
     * <p>Invariant 3: {@code extraHidden} (e.g. Custom ROM / Accessibility / IME preset packages)
     * is hidden even when {@code excludeSystemApps} is true in {@link #MODE_ALLOWLIST}.
     */
    public static boolean isHidden(String mode, Set<String> selected, Set<String> extraHidden,
                                   boolean excludeSystemApps, String pkg, boolean isSystemApp,
                                   String targetPkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (isForcedHidden(pkg)) {
            return true;
        }
        if (isEssentialPackage(pkg, targetPkg)) {
            return false;
        }
        if (extraHidden != null && extraHidden.contains(pkg)) {
            return true;
        }
        String m = normalizeMode(mode);
        if (MODE_ALLOWLIST.equals(m)) {
            if (excludeSystemApps && isSystemApp) {
                return false;
            }
            return selected == null || !selected.contains(pkg);
        }
        if (MODE_DENYLIST.equals(m)) {
            return selected != null && selected.contains(pkg);
        }
        return false;
    }

    /**
     * Sanitizes Android's colon-separated {@code Settings.Secure.enabled_input_methods} value by
     * removing entries whose package is hidden under the active visibility policy.
     */
    public static String filterEnabledInputMethods(String raw, String mode, Set<String> selected,
                                                   Set<String> extraHidden, boolean excludeSystemApps,
                                                   String targetPkg) {
        if (raw == null || raw.trim().isEmpty()) {
            return FALLBACK_IME;
        }
        String[] parts = raw.split(":");
        StringBuilder sb = new StringBuilder();
        for (String entry : parts) {
            if (entry == null || entry.trim().isEmpty()) continue;
            String pkg = extractImePackage(entry);
            boolean isSysGuess = pkg != null && (pkg.startsWith("com.android.")
                    || pkg.startsWith("com.google.android."));
            if (pkg != null && isHidden(mode, selected, extraHidden, excludeSystemApps,
                    pkg, isSysGuess, targetPkg)) {
                continue;
            }
            if (sb.length() > 0) sb.append(':');
            sb.append(entry.trim());
        }
        return sb.length() == 0 ? FALLBACK_IME : sb.toString();
    }

    /** Sanitizes {@code Settings.Secure.default_input_method} if the active IME is hidden. */
    public static String sanitizeDefaultInputMethod(String raw, String mode, Set<String> selected,
                                                    Set<String> extraHidden, boolean excludeSystemApps,
                                                    String targetPkg) {
        if (raw == null || raw.trim().isEmpty()) {
            return FALLBACK_IME;
        }
        String pkg = extractImePackage(raw);
        boolean isSysGuess = pkg != null && (pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.android."));
        if (pkg != null && isHidden(mode, selected, extraHidden, excludeSystemApps,
                pkg, isSysGuess, targetPkg)) {
            return FALLBACK_IME;
        }
        return raw;
    }

    private static String extractImePackage(String entry) {
        String s = entry.trim();
        int semi = s.indexOf(';');
        if (semi > 0) s = s.substring(0, semi);
        int slash = s.indexOf('/');
        String pkg = slash > 0 ? s.substring(0, slash) : s;
        return isValidPackageName(pkg) ? pkg : null;
    }

    /**
     * Extracts a candidate Android package name from common app-data, external-storage, or APK
     * directory paths probed by filesystem-based Applist Detectors.
     */
    public static String extractPackageFromPath(String path) {
        if (path == null || path.length() < 10) {
            return null;
        }
        String norm = path.replace('\\', '/');
        while (norm.contains("//")) {
            norm = norm.replace("//", "/");
        }
        String rest = null;
        if (norm.startsWith("/data/data/")) {
            rest = norm.substring("/data/data/".length());
        } else if (norm.startsWith("/data/user/")) {
            rest = afterNextSlash(norm, "/data/user/".length());
        } else if (norm.startsWith("/data/user_de/")) {
            rest = afterNextSlash(norm, "/data/user_de/".length());
        } else if (norm.startsWith("/data/app/")) {
            String seg = firstSegment(norm.substring("/data/app/".length()));
            if (seg != null && seg.startsWith("~~")) {
                String after = afterNextSlash(norm, "/data/app/".length());
                seg = after != null ? firstSegment(after) : null;
            }
            if (seg != null) {
                int dash = seg.indexOf('-');
                String candidate = dash > 0 ? seg.substring(0, dash) : seg;
                return isValidPackageName(candidate) ? candidate : null;
            }
            return null;
        } else {
            int androidIdx = norm.indexOf("/Android/");
            if (androidIdx >= 0) {
                String tail = norm.substring(androidIdx + "/Android/".length());
                if (tail.startsWith("data/")) {
                    rest = tail.substring("data/".length());
                } else if (tail.startsWith("obb/")) {
                    rest = tail.substring("obb/".length());
                } else if (tail.startsWith("media/")) {
                    rest = tail.substring("media/".length());
                }
            }
        }
        if (rest == null || rest.isEmpty()) {
            return null;
        }
        String pkg = firstSegment(rest);
        return isValidPackageName(pkg) ? pkg : null;
    }

    /**
     * Detects shell commands that attempt to bypass PackageManager hooks via {@code pm} or
     * {@code cmd package}.
     */
    public static boolean isHiddenPmCommand(Object command, String mode, Set<String> selected,
                                            boolean excludeSystemApps, String targetPkg) {
        return isHiddenPmCommand(command, mode, selected, null, excludeSystemApps, targetPkg);
    }

    public static boolean isHiddenPmCommand(Object command, String mode, Set<String> selected,
                                            Set<String> extraHidden, boolean excludeSystemApps,
                                            String targetPkg) {
        String[] tokens = commandTokens(command);
        if (tokens == null || tokens.length < 2) {
            return false;
        }
        String t0 = baseName(tokens[0]);
        if (("sh".equals(t0) || "bash".equals(t0)) && tokens.length >= 3
                && "-c".equals(tokens[1])) {
            tokens = tokens[2].trim().split("\\s+");
            if (tokens.length < 2) {
                return false;
            }
            t0 = baseName(tokens[0]);
        }
        int subIdx;
        if ("pm".equals(t0)) {
            subIdx = 1;
        } else if ("cmd".equals(t0) && tokens.length >= 3 && "package".equals(tokens[1])) {
            subIdx = 2;
        } else {
            return false;
        }
        if (subIdx >= tokens.length) {
            return false;
        }
        String sub = tokens[subIdx];
        if ("list".equals(sub)) {
            for (int i = subIdx + 1; i < tokens.length; i++) {
                if ("packages".equals(tokens[i]) || "package".equals(tokens[i])) {
                    return true;
                }
            }
            return false;
        }
        if ("path".equals(sub) || "dump".equals(sub) || "resolve-activity".equals(sub)
                || "query-activities".equals(sub)) {
            for (int i = subIdx + 1; i < tokens.length; i++) {
                String arg = tokens[i];
                if (arg.startsWith("-")) continue;
                if (isValidPackageName(arg)
                        && isHidden(mode, selected, extraHidden, excludeSystemApps,
                                arg, false, targetPkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String[] commandTokens(Object command) {
        if (command instanceof String) {
            String s = ((String) command).trim();
            return s.isEmpty() ? null : s.split("\\s+");
        }
        if (command instanceof String[]) {
            return (String[]) command;
        }
        if (command instanceof List) {
            List<?> list = (List<?>) command;
            String[] out = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = String.valueOf(list.get(i));
            }
            return out;
        }
        return null;
    }

    private static String baseName(String s) {
        if (s == null) return "";
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String afterNextSlash(String s, int fromIdx) {
        int slash = s.indexOf('/', fromIdx);
        if (slash < 0 || slash + 1 >= s.length()) {
            return null;
        }
        return s.substring(slash + 1);
    }

    private static String firstSegment(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    private static boolean isValidPackageName(String s) {
        if (s == null || s.length() < 3 || s.length() > 160) {
            return false;
        }
        int dotCount = 0;
        if (s.charAt(0) == '.' || s.charAt(s.length() - 1) == '.') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                dotCount++;
                if (i > 0 && s.charAt(i - 1) == '.') {
                    return false;
                }
            } else if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return dotCount >= 1;
    }

    /** Serializes a package set into a JSON string array. */
    public static String toJsonArray(Set<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String p : pkgs) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            String s = p.trim();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\');
                sb.append(c);
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }
}
