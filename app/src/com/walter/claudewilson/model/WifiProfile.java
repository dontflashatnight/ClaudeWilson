package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
        List<Ap> out = new ArrayList<>();
        Random random = new Random(seed ^ 0x57494649L);
        List<Template> pool = templates == null || templates.isEmpty()
                ? fallback(city) : templates;
        int target = Math.max(3, Math.min(14, count + random.nextInt(5) - 2));
        for (int i = 0; i < target; i++) {
            Template template = pick(pool, random, i == 0);
            String ssid = expand(template.ssid, random);
            int frequency = frequency(template.band, random);
            int level = Math.max(-92, Math.min(-35, -42 - random.nextInt(45)));
            String bssid = bssid(random);
            double lat = latitude + (random.nextDouble() - 0.5d) * 0.006d;
            double lng = longitude + (random.nextDouble() - 0.5d) * 0.006d;
            out.add(new Ap(ssid, bssid, frequency, level,
                    capabilities(template.security), i == 0, lat, lng));
        }
        return out;
    }

    public static String json(List<Ap> aps) {
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

    private static List<Template> fallback(String city) {
        List<Template> out = new ArrayList<>();
        String label = city == null || city.isEmpty() ? "Home" : city.replace(" ", "");
        out.add(new Template(label + " WiFi", "WPA2", 5, 5));
        out.add(new Template("Home-{H4}", "WPA2", 2.4, 3));
        out.add(new Template("Guest-{H4}", "WPA2", 2.4, 2));
        out.add(new Template("5G-{H4}", "WPA3", 5, 2));
        return out;
    }

    private static Template pick(List<Template> pool, Random random, boolean connected) {
        if (connected) {
            return pool.get(0);
        }
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

    private static String bssid(Random random) {
        StringBuilder out = new StringBuilder("02");
        for (int i = 0; i < 5; i++) {
            out.append(':').append(String.format(Locale.ROOT, "%02X", random.nextInt(256)));
        }
        return out.toString();
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
