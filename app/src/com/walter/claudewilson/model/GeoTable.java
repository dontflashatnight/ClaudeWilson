package com.walter.claudewilson.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Region coordinates, parsed from {@code catalog/geo.txt}.
 *
 * <p>The card has one row per supported ISO (the country centre, no city name) and optional
 * per-zone rows for countries with more than one time zone. A selected zone wins, so a US
 * persona on America/Los_Angeles is in Los Angeles rather than in Kansas.
 *
 * <p>Pure data: no Android type, no user-facing copy. The table is deliberately tiny and is
 * read once per control-app process; the target process never reads it (the payload carries the
 * resolved point).
 */
public final class GeoTable {

    public static final class Point {
        public final String city;
        public final double latitude;
        public final double longitude;

        Point(String city, double latitude, double longitude) {
            this.city = city == null ? "" : city;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public boolean valid() {
            return latitude >= -90.0d && latitude <= 90.0d
                    && longitude >= -180.0d && longitude <= 180.0d;
        }
    }

    private final Map<String, Point> centers = new HashMap<>();
    private final Map<String, Point> zones = new HashMap<>();

    public static GeoTable parse(String text) {
        GeoTable table = new GeoTable();
        if (text == null) {
            return table;
        }
        for (String line : text.split("\n")) {
            String row = line.trim();
            if (row.isEmpty() || row.startsWith("#")) {
                continue;
            }
            String[] f = row.split("\\|", -1);
            if (f.length < 5) {
                continue;
            }
            String iso = f[0].trim().toLowerCase(Locale.ROOT);
            if (!iso.matches("[a-z]{2}")) {
                continue;
            }
            double lat;
            double lng;
            try {
                lat = Double.parseDouble(f[2].trim());
                lng = Double.parseDouble(f[3].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            Point point = new Point(f[1].trim(), lat, lng);
            if (!point.valid()) {
                continue;
            }
            String zone = f[4].trim();
            if (zone.isEmpty()) {
                if (!table.centers.containsKey(iso)) {
                    table.centers.put(iso, point);
                }
            } else {
                table.zones.put(zone, point);
            }
        }
        return table;
    }

    /** Zone-specific point when known, otherwise the country centre; null when neither exists. */
    public Point point(String iso, String zone) {
        Point zoned = zone == null ? null : zones.get(zone);
        if (zoned != null) {
            return zoned;
        }
        return iso == null ? null : centers.get(iso.toLowerCase(Locale.ROOT));
    }

    public int centers() {
        return centers.size();
    }

    public int zones() {
        return zones.size();
    }
}
