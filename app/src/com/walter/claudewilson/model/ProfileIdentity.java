package com.walter.claudewilson.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Stable identity of a device profile, independent of its position in the catalog.
 *
 * <p>The radio profile (BSSID / BLE addresses) must not be keyed by the picker index: an
 * unlisted or custom model would fall back to index 0 and share the running device's values.
 * This identity is built from the model fields themselves, so any model -- catalog, self or
 * custom -- gets its own radio identity.
 */
public final class ProfileIdentity {
    private ProfileIdentity() {
    }

    /** A 14-field persona row: manufacturer, brand, model, device, product. */
    public static String of(String[] device) {
        if (device == null || device.length < 6) {
            return "unknown";
        }
        return part(device[1]) + "|" + part(device[2]) + "|" + part(device[3]) + "|"
                + part(device[4]) + "|" + part(device[5]);
    }

    /** Short, filename-safe key for preference storage. */
    public static String key(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xFF));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(identity.hashCode());
        }
    }

    private static String part(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
