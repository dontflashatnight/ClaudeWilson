package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Virtual Wi-Fi neighbourhood for one region.
 *
 * <p>A profile library supplies region-specific SSID patterns; the seed picks how many APs exist,
 * which patterns appear, channels, BSSIDs and RSSI. The result is stable for one persona and
 * different from another persona in the same region, instead of one fixed scan list for everyone.
 */
public final class WifiProfile {
    public static final class Template {
        public final String ssid;
        public final String security;
        public final double band;
        public final int weight;

        public Template(String ssid, String security, double band, int weight) {
            this.ssid = ssid == null ? "" : ssid;
            this.security = security == null ? "WPA2" : security;
            this.band = band;
            this.weight = Math.max(1, weight);
        }
    }

    public static final class Ap {
        public final String ssid;
        public final String bssid;
        public final int frequency;
        public final int level;
        public final String capabilities;
        public final boolean connected;
        public final double latitude;
        public final double longitude;

        public Ap(String ssid, String bssid, int frequency, int level, String capabilities,
                boolean connected, double latitude, double longitude) {
            this.ssid = ssid == null ? "" : ssid;
            this.bssid = bssid == null ? "" : bssid.toUpperCase(Locale.ROOT);
            this.frequency = frequency;
            this.level = level;
            this.capabilities = capabilities == null ? "[ESS]" : capabilities;
            this.connected = connected;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private WifiProfile() {
    }

    /** Fallback when the catalog is missing; still varied by seed. */
    public static List<Ap> forRegion(double latitude, double longitude, String city, long seed,
            int count) {
        return forRegion(latitude, longitude, city, seed, count, new ArrayList<Template>());
    }

    public static List<Ap> forRegion(double latitude, double longitude, String city, long seed,
            int count, List<Template> templates) {
        return forRegion(latitude, longitude, city, seed, count, templates, null);
    }

    public static List<Ap> forRegion(double latitude, double longitude, String city, long seed,
            int count, List<Template> templates, Map<String, List<String>> oui) {
        List<Ap> out = new ArrayList<>();
        Random random = new Random(seed ^ 0x57494649L);
        List<Template> pool = templates == null || templates.isEmpty()
                ? fallback(city) : templates;
        int target = Math.max(3, Math.min(14, count + random.nextInt(5) - 2));
        Set<String> usedBssids = new HashSet<>();
        Set<String> usedSsids = new HashSet<>();
        for (int i = 0; i < target; i++) {
            Template template = pick(pool, random);
            String ssid = expand(template.ssid, random);
            int sRetry = 0;
            while (!usedSsids.add(ssid) && sRetry++ < 8) {
                template = pick(pool, random);
                ssid = expand(template.ssid, random);
            }
            int frequency = frequency(template.band, random);
            int level = Math.max(-92, Math.min(-35, -42 - random.nextInt(45)));
            String bssid = bssid(ssid, random, oui);
            int retry = 0;
            while (!usedBssids.add(bssid) && retry++ < 8) {
                bssid = bssid(ssid, random, oui);
            }
            double lat = latitude + (random.nextDouble() - 0.5d) * 0.006d;
            double lng = longitude + (random.nextDouble() - 0.5d) * 0.006d;
            out.add(new Ap(ssid, bssid, frequency, level,
                    capabilities(template.security), i == 0, lat, lng));
        }
        return out;
    }

    public static String json(List<Ap> aps) {
        if (aps == null || aps.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < aps.size(); i++) {
            Ap ap = aps.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"ssid\":\"").append(escape(ap.ssid))
                    .append("\",\"bssid\":\"").append(escape(ap.bssid))
                    .append("\",\"frequency\":").append(ap.frequency)
                    .append(",\"level\":").append(ap.level)
                    .append(",\"capabilities\":\"").append(escape(ap.capabilities))
                    .append("\",\"connected\":").append(ap.connected)
                    .append(",\"latitude\":").append(ap.latitude)
                    .append(",\"longitude\":").append(ap.longitude)
                    .append('}');
        }
        return out.append(']').toString();
    }

    /** Validates a standard 6-octet colon-separated MAC address. */
    public static boolean isValidMac(String mac) {
        if (mac == null) {
            return false;
        }
        String s = mac.trim();
        if (s.length() != 17) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            char c = s.charAt(i);
            if (i % 3 == 2) {
                if (c != ':') return false;
            } else {
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
        }
        return true;
    }

    /** Generates a deterministic unicast Wi-Fi BSSID using common consumer router OUIs. */
    public static String randomMac(long seed) {
        String[] ouis = {"3C:84:6A", "B0:BE:76", "E4:F0:42", "AC:84:C6", "28:EE:52", "F0:9F:C2"};
        Random random = new Random(seed ^ 0x4D4143L);
        String prefix = ouis[random.nextInt(ouis.length)];
        return String.format(Locale.US, "%s:%02X:%02X:%02X",
                prefix, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    /** Applies optional custom connected SSID / BSSID overrides to the primary AP. */
    public static List<Ap> applyOverrides(List<Ap> base, String customSsid, String customBssid) {
        if (base == null || base.isEmpty()) {
            return new ArrayList<Ap>();
        }
        String ssidOverride = customSsid == null ? "" : customSsid.trim();
        if (ssidOverride.length() > 32) {
            ssidOverride = ssidOverride.substring(0, 32);
        }
        String bssidOverride = isValidMac(customBssid)
                ? customBssid.trim().toUpperCase(Locale.ROOT) : "";
        if (ssidOverride.isEmpty() && bssidOverride.isEmpty()) {
            return base;
        }
        List<Ap> out = new ArrayList<Ap>(base.size());
        for (int i = 0; i < base.size(); i++) {
            Ap ap = base.get(i);
            if (i == 0 || ap.connected) {
                out.add(new Ap(
                        ssidOverride.isEmpty() ? ap.ssid : ssidOverride,
                        bssidOverride.isEmpty() ? ap.bssid : bssidOverride,
                        ap.frequency, ap.level, ap.capabilities, true, ap.latitude, ap.longitude));
            } else {
                out.add(ap);
            }
        }
        return out;
    }

    private static List<Template> fallback(String city) {
        List<Template> out = new ArrayList<>();
        String label = city == null || city.isEmpty() ? "Home" : city.replace(" ", "");
        out.add(new Template(label + " WiFi", "WPA2", 5, 5));
        out.add(new Template("Home-{H4}", "WPA2", 2.4, 3));
        out.add(new Template("Guest-{H4}", "WPA2", 2.4, 2));
        out.add(new Template("5G-{H4}", "WPA3", 5, 2));
        return out;
    }

    private static Template pick(List<Template> pool, Random random) {
        int total = 0;
        for (Template template : pool) {
            total += template.weight;
        }
        int value = random.nextInt(Math.max(1, total));
        for (Template template : pool) {
            value -= template.weight;
            if (value < 0) {
                return template;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static String expand(String template, Random random) {
        String value = template.replace("{H4}", hex(random, 4))
                .replace("{H3}", hex(random, 3));
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static int frequency(double band, Random random) {
        if (band >= 6.0d) {
            return 5955 + random.nextInt(4) * 20;
        }
        if (band >= 5.0d) {
            int[] channels = {5180, 5200, 5220, 5240, 5745, 5765, 5785};
            return channels[random.nextInt(channels.length)];
        }
        int[] channels = {2412, 2437, 2462, 2467};
        return channels[random.nextInt(channels.length)];
    }

    private static String capabilities(String security) {
        if ("WPA3".equals(security)) {
            return "[WPA3-SAE][ESS]";
        }
        if ("OPEN".equals(security)) {
            return "[ESS]";
        }
        return "[WPA2-PSK][ESS]";
    }

    private static String bssid(String ssid, Random random,
            Map<String, List<String>> oui) {
        String prefix = prefix(ssid, random, oui);
        StringBuilder out = new StringBuilder(prefix);
        for (int i = 0; i < 3; i++) {
            out.append(':').append(String.format(Locale.ROOT, "%02X", random.nextInt(256)));
        }
        return out.toString();
    }

    private static String prefix(String ssid, Random random, Map<String, List<String>> oui) {
        String vendor = vendor(ssid);
        if (oui != null) {
            List<String> exact = vendor == null ? null : oui.get(vendor);
            if (exact != null && !exact.isEmpty()) {
                return exact.get(random.nextInt(exact.size()));
            }
            List<String> generic = oui.get("GENERIC");
            if (generic != null && !generic.isEmpty()) {
                return generic.get(random.nextInt(generic.size()));
            }
        }
        return "02:00:00";
    }

    private static String vendor(String ssid) {
        String value = ssid == null ? "" : ssid.toLowerCase(Locale.ROOT);
        if (value.contains("docomo") || value.contains("nuro")) return "NTT";
        if (value.contains("buffalo")) return "BUFFALO";
        if (value.contains("aterm") || value.contains("elecom")) return "NEC";
        if (value.contains("iptime")) return "EFM";
        if (value.contains("kt_giga") || value.contains("sk_wifi")
                || value.contains("u+net")) return "ARCADYAN";
        if (value.contains("tp-link")) return "TP-LINK";
        if (value.contains("asus")) return "ASUS";
        if (value.contains("eero")) return "EERO";
        if (value.contains("netgear")) return "NETGEAR";
        if (value.contains("linksys")) return "LINKSYS";
        if (value.contains("d-link")) return "D-LINK";
        if (value.contains("fritz!box")) return "AVM";
        if (value.contains("xfinity") || value.contains("spectrum")
                || value.contains("coxwifi") || value.contains("shaw-")) return "ARRIS";
        if (value.contains("att-") || value.contains("verizon-")
                || value.contains("bt-") || value.contains("sky")
                || value.contains("telstra") || value.contains("vivo-")
                || value.contains("claro") || value.contains("tim")) return "TECHNICOLOR";
        if (value.contains("singtel") || value.contains("starhub")
                || value.contains("m1-") || value.contains("livebox")
                || value.contains("sfr_") || value.contains("bbox")
                || value.contains("rogers") || value.contains("bell")
                || value.contains("telus") || value.contains("ziggo")
                || value.contains("kpn-") || value.contains("proximus")
                || value.contains("swisscom") || value.contains("movistar")
                || value.contains("funbox") || value.contains("optus")) return "SAGEMCOM";
        if (value.contains("telekom") || value.contains("vodafone")
                || value.contains("o2-") || value.contains("a1-wlan")
                || value.contains("magenta") || value.contains("tmobile")) return "ARCADYAN";
        if (value.contains("jiofiber") || value.contains("airtel")
                || value.contains("act-") || value.contains("pldt")
                || value.contains("globe") || value.contains("aisfibre")) return "SERCOMM";
        if (value.contains("converge") || value.contains("unifi-")
                || value.contains("indihome") || value.contains("vnpt")
                || value.contains("viettel") || value.contains("etisalat")
                || value.contains("stc-")) return "NOKIA";
        if (value.contains("hinet") || value.contains("cht-")
                || value.contains("zyxel")) return "ZYXEL";
        if (value.contains("googlefiber")) return "GOOGLE";
        if (value.contains("freebox") || value.contains("iliadbox")) return "SAGEMCOM";
        if (value.contains("iinet") || value.contains("spark-")
                || value.contains("telia") || value.contains("telenor")) return "NETGEAR";
        return null;
    }

    private static String hex(Random random, int digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            out.append(Integer.toHexString(random.nextInt(16)).toUpperCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
