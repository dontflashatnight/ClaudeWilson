package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Virtual Wi-Fi neighbourhood for one region. Adapted from VirtualRegion's fixed-AP generator:
 * a connected AP plus a handful of nearby APs, valid BSSIDs, 2.4/5 GHz frequencies, RSSI and
 * capability strings. No real scan result is ever read.
 */
public final class WifiProfile {
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

    public static List<Ap> forRegion(double latitude, double longitude, String city, long seed,
            int count) {
        List<Ap> out = new ArrayList<>();
        Random random = new Random(seed ^ 0x57494649L);
        String label = city == null || city.isEmpty() ? "Home" : city.replace(" ", "");
        int total = Math.max(3, Math.min(12, count));
        for (int i = 0; i < total; i++) {
            String ssid;
            String capabilities;
            if (i == 0) {
                ssid = label + " WiFi";
                capabilities = "[WPA2-PSK][WPA3-SAE][ESS]";
            } else if (i % 4 == 1) {
                ssid = label + " Guest";
                capabilities = "[WPA2-PSK][ESS]";
            } else if (i % 4 == 2) {
                ssid = "TP-LINK_" + hex(random, 4);
                capabilities = "[WPA2-PSK][ESS]";
            } else if (i % 4 == 3) {
                ssid = "5G-" + hex(random, 4);
                capabilities = "[WPA3-SAE][ESS]";
            } else {
                ssid = "Cafe-" + hex(random, 3);
                capabilities = "[ESS]";
            }
            int frequency = i % 3 == 0 ? 5180 + (i / 3) * 20 : 2412 + (i % 3) * 25;
            int level = Math.max(-92, Math.min(-38, -44 - random.nextInt(38)));
            String bssid = bssid(random);
            double lat = latitude + (random.nextDouble() - 0.5d) * 0.004d;
            double lng = longitude + (random.nextDouble() - 0.5d) * 0.004d;
            out.add(new Ap(ssid, bssid, frequency, level, capabilities, i == 0, lat, lng));
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
