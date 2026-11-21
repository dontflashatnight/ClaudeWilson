package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses pasted coordinates and map links into WGS-84 {@code (latitude, longitude)} pairs.
 *
 * <p>Supports plain decimal pairs ({@code "35.6895, 139.6917"}), {@code geo:} URIs, Google Maps
 * {@code @lat,lng} / {@code ?q=lat,lng} / {@code !3dlat!4dlng} links, and DMS notation
 * ({@code 35°41'22"N 139°41'30"E}).
 */
public final class CoordinateParser {

    public static final class LatLng {
        public final double latitude;
        public final double longitude;

        public LatLng(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final Pattern GOOGLE_3D4D = Pattern.compile(
            "!3d(-?\\d{1,2}(?:\\.\\d+)?)!4d(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern AT_OR_QUERY = Pattern.compile(
            "(?:@|[?&](?:q|ll|center|destination|query)=|geo:)(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern DMS_PAIR = Pattern.compile(
            "(\\d{1,2})\\s*\\u00B0\\s*(\\d{1,2})\\s*['\\u2032]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*[\"\\u2033]?\\s*([NSns])"
                    + "[\\s,;]+"
                    + "(\\d{1,3})\\s*\\u00B0\\s*(\\d{1,2})\\s*['\\u2032]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*[\"\\u2033]?\\s*([EWew])");
    private static final Pattern DECIMAL_PAIR = Pattern.compile(
            "^\\s*\\(?\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*\\)?\\s*$");

    private CoordinateParser() {
    }

    public static LatLng parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        Matcher m = GOOGLE_3D4D.matcher(text);
        if (m.find()) {
            return valid(m.group(1), m.group(2));
        }
        m = AT_OR_QUERY.matcher(text);
        if (m.find()) {
            return valid(m.group(1), m.group(2));
        }
        m = DMS_PAIR.matcher(text);
        if (m.find()) {
            try {
                double lat = dms(m.group(1), m.group(2), m.group(3), m.group(4));
                double lng = dms(m.group(5), m.group(6), m.group(7), m.group(8));
                return valid(lat, lng);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        m = DECIMAL_PAIR.matcher(text);
        if (m.matches()) {
            return valid(m.group(1), m.group(2));
        }
        return null;
    }

    private static double dms(String deg, String min, String sec, String hemi) {
        double value = Double.parseDouble(deg)
                + Double.parseDouble(min) / 60.0d
                + Double.parseDouble(sec) / 3600.0d;
        String h = hemi.toUpperCase(Locale.ROOT);
        return ("S".equals(h) || "W".equals(h)) ? -value : value;
    }

    private static LatLng valid(String latStr, String lngStr) {
        try {
            return valid(Double.parseDouble(latStr), Double.parseDouble(lngStr));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LatLng valid(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)) {
            return null;
        }
        if (lat < -90.0d || lat > 90.0d || lng < -180.0d || lng > 180.0d) {
            return null;
        }
        return new LatLng(lat, lng);
    }
}
