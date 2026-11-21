package com.walter.claudewilson.model;

import java.util.Locale;

/**
 * Pure-Java network leak & routing verdict rules (zero Android imports, zero CJK).
 *
 * <p>Covers:
 * <ul>
 *   <li>Android {@code dumpsys connectivity} VPN {@code UidRanges} membership parsing;</li>
 *   <li>Private DNS ({@code private_dns_mode=hostname}) tunnel-bypass detection;</li>
 *   <li>IPv6 dual-stack local-address and cross-country leak classification;</li>
 *   <li>Datacenter / hosting ASN classification combining geoip flags and org tokens.</li>
 * </ul>
 */
public final class NetworkRules {
    public static final int V6_CLEAN_NONE = 1;
    public static final int V6_TUNNEL_ALIGNED = 2;
    public static final int V6_LEAK_LOCAL = 3;
    public static final int V6_LEAK_CROSS_COUNTRY = 4;

    private static final String[] DC_ASN = {
            "amazon", "aws", "google", "microsoft", "azure", "digitalocean",
            "vultr", "linode", "akamai", "cloudflare", "ovh", "hetzner",
            "alibaba", "aliyun", "tencent", "oracle", "datacamp", "m247",
            "choopa", "contabo", "leaseweb", "astrix", "hosting", "datacenter",
            "data center", "server", "cdn", "gsl", "gsl networks",
            "g-core", "gcore", "cloud", "vps", "fastly", "equinix",
            "scaleway", "zenlayer", "xtom", "dmit", "it7", "bandwagon",
            "quadranet", "psychz", "packethub", "clouvider", "misaka",
            "hostinger", "ionos", "rackspace", "bunny", "byteplus", "huawei"
    };

    private static final String[] DOMESTIC_DOT_TOKENS = {
            "alidns", "dnspod", "dot.pub", "doh.pub", "360.cn", "rubyfish",
            "tsinghua", "ustc", "baidu", "cnnic", "bytedance"
    };

    private NetworkRules() {
    }

    /**
     * Evaluates whether {@code uid} falls inside a {@code dumpsys connectivity} {@code UidRanges}
     * string such as {@code "[0-10228, 10230-99999]"} or {@code "10000-19999"}.
     */
    public static boolean uidInRanges(int uid, String rangesSpec) {
        if (uid < 0 || rangesSpec == null || rangesSpec.trim().isEmpty()) {
            return false;
        }
        String cleaned = rangesSpec.replace('[', ' ').replace(']', ' ')
                .replace('{', ' ').replace('}', ' ')
                .replace('<', ' ').replace('>', ' ').trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        String[] tokens = cleaned.split("[,\\s]+");
        for (String tok : tokens) {
            if (tok == null || tok.isEmpty()) continue;
            int dash = tok.indexOf('-');
            try {
                if (dash > 0 && dash + 1 < tok.length()) {
                    int lo = Integer.parseInt(tok.substring(0, dash).trim());
                    int hi = Integer.parseInt(tok.substring(dash + 1).trim());
                    if (uid >= lo && uid <= hi) {
                        return true;
                    }
                } else {
                    int exact = Integer.parseInt(tok.trim());
                    if (uid == exact) {
                        return true;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    /**
     * True when Android Private DNS is forced to a custom DoT hostname ({@code mode=hostname}),
     * which resolves outside typical VPN/TProxy port-53 DNS interception.
     */
    public static boolean isPrivateDnsBypassRisk(String mode, String specifier) {
        if (mode == null) {
            return false;
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!"hostname".equals(m)) {
            return false;
        }
        String spec = specifier == null ? "" : specifier.trim();
        return !spec.isEmpty() && !"null".equalsIgnoreCase(spec);
    }

    /** True when the configured Private DNS hostname points to a known domestic CN resolver. */
    public static boolean isDomesticPrivateDns(String specifier) {
        if (specifier == null || specifier.trim().isEmpty()) {
            return false;
        }
        String lower = specifier.trim().toLowerCase(Locale.ROOT);
        for (String tok : DOMESTIC_DOT_TOKENS) {
            if (lower.contains(tok)) {
                return true;
            }
        }
        return false;
    }

    /** True when an address belongs to standard proxy Fake-IP space (198.18.0.0/15 or fc00::/7). */
    public static boolean isFakeIp(String addr) {
        if (addr == null || addr.isEmpty()) {
            return false;
        }
        String a = addr.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("198.18.") || a.startsWith("198.19.")) {
            return true;
        }
        return a.contains(":") && (a.startsWith("fc") || a.startsWith("fd"));
    }

    /** Combines geoip {@code hosting/proxy} flags with ASN organization keywords. */
    public static boolean isDatacenterOrg(String org, boolean proxyFlag, boolean hostingFlag) {
        if (hostingFlag || proxyFlag) {
            return true;
        }
        if (org == null || org.trim().isEmpty()) {
            return false;
        }
        String lower = org.toLowerCase(Locale.ROOT);
        for (String k : DC_ASN) {
            if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Classifies IPv6 egress against local device interfaces AND IPv4 egress country to catch both
     * direct local IPv6 leaks and dual-stack v4/v6 cross-country mismatches.
     */
    public static int classifyV6(String v6Ip, String v6Cc, String localV6Blob,
                                 String v4Cc, boolean v6Restricted) {
        String ip = v6Ip == null ? "" : v6Ip.trim();
        if (ip.isEmpty() || "none".equalsIgnoreCase(ip)) {
            return V6_CLEAN_NONE;
        }
        if (localV6Blob != null && !localV6Blob.isEmpty() && localV6Blob.contains(ip)) {
            return V6_LEAK_LOCAL;
        }
        String cc6 = v6Cc == null ? "" : v6Cc.trim().toUpperCase(Locale.ROOT);
        String cc4 = v4Cc == null ? "" : v4Cc.trim().toUpperCase(Locale.ROOT);
        if (v6Restricted) {
            return V6_LEAK_CROSS_COUNTRY;
        }
        if (!cc6.isEmpty() && !cc4.isEmpty() && !cc6.equalsIgnoreCase(cc4)) {
            return V6_LEAK_CROSS_COUNTRY;
        }
        return V6_TUNNEL_ALIGNED;
    }

    /** True for standard cellular modem interface names that must never be hidden as VPN tunnels. */
    public static boolean isCellularInterfaceName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String low = name.trim().toLowerCase(Locale.ROOT);
        return low.startsWith("rmnet")
                || low.startsWith("r_rmnet")
                || low.startsWith("rev_rmnet")
                || low.startsWith("cdma_rmnet")
                || low.startsWith("ccmni")
                || low.startsWith("pdp")
                || low.startsWith("umts")
                || low.startsWith("svnet")
                || low.startsWith("seth_")
                || low.startsWith("wwan")
                || low.startsWith("ril")
                || low.startsWith("clat")
                || low.startsWith("epdg")
                || low.startsWith("v4-rmnet")
                || low.startsWith("v4-ccmni")
                || low.startsWith("v4-pdp");
    }

    /** Fallback prefix check for VPN/proxy interfaces spawned after config publish. */
    public static boolean isKnownTunnelPrefix(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String low = name.trim().toLowerCase(Locale.ROOT);
        return low.startsWith("tun")
                || low.startsWith("tap")
                || low.startsWith("ppp")
                || low.startsWith("wg")
                || low.startsWith("utun")
                || low.startsWith("clash")
                || low.startsWith("netproxy")
                || low.startsWith("singbox")
                || low.startsWith("sing-box")
                || low.startsWith("mihomo")
                || "meta".equals(low)
                || low.startsWith("meta0")
                || low.startsWith("meta1")
                || low.startsWith("meta_")
                || low.startsWith("meta-")
                || low.startsWith("xray")
                || low.startsWith("v2ray")
                || low.startsWith("nekobox")
                || low.startsWith("flclash")
                || low.startsWith("hiddify")
                || low.startsWith("karing")
                || low.startsWith("exclave")
                || low.startsWith("surfboard")
                || low.startsWith("shadowsocks")
                || low.startsWith("awg")
                || low.startsWith("amnezia")
                || low.startsWith("warp")
                || low.startsWith("ovpn")
                || low.startsWith("zerotier")
                || low.startsWith("adguard")
                || low.startsWith("tproxy")
                || low.startsWith("tailscale")
                || low.startsWith("ipsec");
    }

    /**
     * True when an IPv4 DNS address on a tunnel {@code LinkProperties} is a synthetic TUN-peer,
     * Fake-IP, CGNAT, or private/loopback address rather than a public resolver.
     *
     * <p>Covers sing-box/FlClash/Hiddify/Karing ({@code 172.19.0.2}, {@code 172.16.0.0/12}),
     * Mihomo/Clash ({@code 198.18.0.2}, {@code 198.18.0.0/15}), v2rayNG/Xray/Surfboard/Outline
     * ({@code 10.10.14.2}, {@code 10.0.0.0/8}, {@code 26.26.26.2}), Tailscale ({@code 100.64.0.0/10}),
     * and RFC1918/loopback/link-local addresses.
     */
    public static boolean isTunnelPeerOrPrivateDns(byte[] ipv4) {
        if (ipv4 == null || ipv4.length != 4) {
            return false;
        }
        int b0 = ipv4[0] & 0xFF;
        int b1 = ipv4[1] & 0xFF;
        int b2 = ipv4[2] & 0xFF;
        if (b0 == 10 || b0 == 127 || b0 == 0) {
            return true;
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true;
        }
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return true;
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return true;
        }
        return b0 == 26 && b1 == 26 && b2 == 26;
    }

    /**
     * Sanitizes {@code NetworkCapabilities.toString()} output so VPN transports and
     * {@code VpnTransportInfo{sessionId=...}} are never leaked through string inspection.
     */
    public static String sanitizeCapabilitiesString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = raw;
        if (out.contains("VPN")) {
            out = out.replace("Transports: WIFI|VPN ", "Transports: WIFI ")
                    .replace("Transports: VPN|WIFI ", "Transports: WIFI ")
                    .replace("Transports: CELLULAR|VPN ", "Transports: CELLULAR ")
                    .replace("Transports: VPN|CELLULAR ", "Transports: CELLULAR ")
                    .replace("Transports: ETHERNET|VPN ", "Transports: ETHERNET ")
                    .replace("Transports: VPN|ETHERNET ", "Transports: ETHERNET ")
                    .replace("Transports: VPN ", "Transports: WIFI ")
                    .replace("|VPN", "")
                    .replace("VPN|", "");
            if (out.contains("Capabilities: ") && !out.contains("NOT_VPN")) {
                out = out.replace("Capabilities: ", "Capabilities: NOT_VPN&");
            }
        }
        int ti = out.indexOf(" TransportInfo: <VpnTransportInfo");
        if (ti >= 0) {
            int end = out.indexOf(">", ti);
            if (end > ti) {
                out = out.substring(0, ti) + out.substring(end + 1);
            }
        }
        return out;
    }

    /** Sanitizes {@code NetworkInfo.toString()} when the active network is reported as VPN. */
    public static String sanitizeNetworkInfoString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = raw;
        out = out.replace("type: VPN[", "type: WIFI[")
                .replace("type: VPN,", "type: WIFI,");
        int ex = out.indexOf("extra: VPN:");
        if (ex >= 0) {
            int comma = out.indexOf(',', ex);
            if (comma > ex) {
                out = out.substring(0, ex) + "extra: (none)" + out.substring(comma);
            } else {
                out = out.substring(0, ex) + "extra: (none)";
            }
        }
        return out;
    }

    /**
     * Dynamically extracts virtual VPN/proxy/tunnel interface names present on this device from
     * {@code ip -o link show} and {@code ls /sys/class/net/&#42;/tun_flags}.
     */
    public static java.util.List<String> parseTunnelInterfaces(String ipLinkOut,
                                                               String tunFlagsOut) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<String>();
        if (tunFlagsOut != null && !tunFlagsOut.isEmpty()) {
            for (String raw : tunFlagsOut.split("\\n")) {
                String line = raw.trim();
                String prefix = "/sys/class/net/";
                String suffix = "/tun_flags";
                int start = line.indexOf(prefix);
                int end = line.indexOf(suffix);
                if (start >= 0 && end > start + prefix.length()) {
                    String name = cleanIfaceToken(line.substring(start + prefix.length(), end));
                    if (!name.isEmpty() && !"lo".equals(name) && !isCellularInterfaceName(name)) {
                        found.add(name);
                    }
                }
            }
        }
        if (ipLinkOut != null && !ipLinkOut.isEmpty()) {
            for (String raw : ipLinkOut.split("\\n")) {
                String line = raw.trim();
                int firstColon = line.indexOf(':');
                if (firstColon <= 0) {
                    continue;
                }
                int secondColon = line.indexOf(':', firstColon + 1);
                if (secondColon <= firstColon + 1) {
                    continue;
                }
                String name = cleanIfaceToken(line.substring(firstColon + 1, secondColon));
                if (name.isEmpty() || "lo".equals(name) || isCellularInterfaceName(name)) {
                    continue;
                }
                String rest = line.substring(secondColon + 1);
                int lt = rest.indexOf('<');
                int gt = rest.indexOf('>', lt + 1);
                String flags = (lt >= 0 && gt > lt) ? rest.substring(lt + 1, gt) : "";
                boolean p2p = flags.contains("POINTOPOINT");
                boolean linkNone = rest.contains("link/none");
                if (p2p || linkNone || isKnownTunnelPrefix(name)) {
                    found.add(name);
                }
            }
        }
        return new java.util.ArrayList<String>(found);
    }

    private static String cleanIfaceToken(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int at = s.indexOf('@');
        if (at > 0) {
            s = s.substring(0, at);
        }
        if (s.length() > 32 || !s.matches("[A-Za-z0-9._-]+")) {
            return "";
        }
        return s;
    }
}
