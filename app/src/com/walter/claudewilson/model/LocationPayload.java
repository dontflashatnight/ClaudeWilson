package com.walter.claudewilson.model;

/**
 * The {@code location} object inside a persona payload.
 *
 * <p>Serialising this as its own object, instead of appending five properties to the flat payload
 * by hand, keeps the quote pairing local: the caller writes {@code ,"location":<object>} and the
 * object always closes itself. The first device test of 0.5.0 caught a doubled quote in the
 * hand-built form, which made the whole persona unparseable in the target process.
 */
public final class LocationPayload {
    private LocationPayload() {
    }

    public static String json(boolean enabled, double latitude, double longitude, long seed,
            String city) {
        return json(enabled, latitude, longitude, seed, city, "fine", Double.NaN);
    }

    public static String json(boolean enabled, double latitude, double longitude, long seed,
            String city, String accuracyMode, double altitude) {
        String mode = accuracyMode == null || accuracyMode.isEmpty() ? "fine" : accuracyMode;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"enabled\":").append(enabled)
                .append(",\"latitude\":").append(latitude)
                .append(",\"longitude\":").append(longitude)
                .append(",\"seed\":").append(seed)
                .append(",\"city\":\"").append(escape(city)).append('"');
        if (!"fine".equals(mode)) {
            sb.append(",\"accuracyMode\":\"").append(escape(mode)).append('"');
        }
        if (!Double.isNaN(altitude) && !Double.isInfinite(altitude)) {
            sb.append(",\"altitude\":").append(altitude);
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
