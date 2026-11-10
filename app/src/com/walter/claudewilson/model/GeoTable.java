package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
        public final String iso;
        public final String city;
        public final double latitude;
        public final double longitude;
        public final String zone;

        Point(String iso, String city, double latitude, double longitude, String zone) {
            this.iso = iso == null ? "" : iso;
            this.city = city == null ? "" : city;
            this.latitude = latitude;
            this.longitude = longitude;
            this.zone = zone == null ? "" : zone;
        }

        /** A caller-supplied point (map picker); no ISO or zone. */
        public static Point of(String city, double latitude, double longitude) {
            return new Point("", city, latitude, longitude, "");
        }

        public boolean valid() {
            return latitude >= -90.0d && latitude <= 90.0d
                    && longitude >= -180.0d && longitude <= 180.0d;
        }
    }

    private final Map<String, Point> centers = new HashMap<>();
    private final Map<String, Point> zones = new HashMap<>();
    private final List<Point> points = new ArrayList<>();

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
            String zone = f[4].trim();
            Point point = new Point(iso, f[1].trim(), lat, lng, zone);
            if (!point.valid()) {
                continue;
            }
            table.points.add(point);
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

    /** Every parsed row, country centres and zone cities. */
    public List<Point> all() {
        return Collections.unmodifiableList(points);
    }

    public int centers() {
        return centers.size();
    }

    public int zones() {
        return zones.size();
    }

    /** Nearest known point, or null when the table is empty. */
    public Point nearest(double latitude, double longitude) {
        Point best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Point point : points) {
            double distance = distanceKm(latitude, longitude, point.latitude, point.longitude);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = point;
            }
        }
        return best;
    }

    /**
     * Offline search over city, ISO and zone. Country-centre rows have no city name and only
     * match their ISO; zone rows are preferred when both describe the same place.
     */
    public List<Point> search(String query, int limit) {
        List<Point> out = new ArrayList<>();
        if (query == null) {
            return out;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return out;
        }
        for (Point point : points) {
            if (!point.city.isEmpty() && point.city.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(point);
            }
        }
        for (Point point : points) {
            if (out.size() >= limit) {
                break;
            }
            if (point.city.isEmpty() && point.iso.equals(q) && !out.contains(point)) {
                out.add(point);
            }
        }
        for (Point point : points) {
            if (out.size() >= limit) {
                break;
            }
            if (!point.zone.isEmpty() && point.zone.toLowerCase(Locale.ROOT).contains(q)
                    && !out.contains(point)) {
                out.add(point);
            }
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2.0d) * Math.sin(dLat / 2.0d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2.0d) * Math.sin(dLng / 2.0d);
        return 12742.0d * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0d, 1.0d - a)));
    }
}
