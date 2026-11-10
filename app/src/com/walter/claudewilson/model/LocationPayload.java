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
        return "{\"enabled\":" + enabled
                + ",\"latitude\":" + latitude
                + ",\"longitude\":" + longitude
                + ",\"seed\":" + seed
                + ",\"city\":\"" + escape(city) + "\"}";
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
