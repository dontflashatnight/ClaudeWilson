package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Pure validation and linkage rules for persona editing menus.
 *
 * <p>No Android imports and no user-facing copy: tested off-device by {@code test.py}.
 */
public final class PersonaEditRules {

    private PersonaEditRules() {
    }

    public static boolean validOperatorCode(String op) {
        return op != null && op.matches("\\d{5,6}");
    }

    public static boolean validCarrierName(String value) {
        if (value == null || value.isEmpty() || value.length() > 48) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < 0x20) {
                return false;
            }
        }
        return true;
    }

    public static boolean validAid(String value) {
        return value != null && value.matches("[0-9a-f]{16}");
    }

    /** Generates a 16-character lowercase hex Android ID from 64 random bits. */
    public static String randomAid(Random rng) {
        long bits = rng.nextLong();
        String hex = Long.toHexString(bits);
        if (hex.length() == 16) {
            return hex;
        }
        StringBuilder sb = new StringBuilder(16);
        for (int i = hex.length(); i < 16; i++) {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }

    /** True when the 3-digit MCC of {@code op} appears in {@code regionMccList} (e.g. "310/311"). */
    public static boolean mccMatchesRegion(String op, String regionMccList) {
        if (!validOperatorCode(op) || regionMccList == null || regionMccList.isEmpty()) {
            return false;
        }
        String mcc = op.substring(0, 3);
        for (String part : regionMccList.split("/")) {
            if (mcc.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Curated AOSP SubscriptionInfo carrier IDs for major operators, or -1 when generic. */
    public static int carrierIdFor(String op, String carrier) {
        if (op != null) {
            if ("44010".equals(op)) return 1185;
            if ("310260".equals(op)) return 1;
            if ("310410".equals(op)) return 1187;
            if ("311480".equals(op)) return 1839;
            if ("23430".equals(op)) return 1492;
            if ("26201".equals(op)) return 1429;
            if ("20801".equals(op)) return 1419;
            if ("50501".equals(op)) return 1345;
            if ("45005".equals(op)) return 1891;
        }
        if (carrier != null) {
            String low = carrier.trim().toLowerCase(Locale.ROOT);
            if ("docomo".equals(low)) return 1185;
            if ("t-mobile".equals(low)) return 1;
            if ("at&t".equals(low)) return 1187;
            if ("verizon".equals(low)) return 1839;
        }
        return -1;
    }

    /** True when two valid IANA timezone IDs have different UTC offsets at {@code atEpochMs}. */
    public static boolean tzOffsetDiffers(String zoneA, String zoneB, long atEpochMs) {
        if (zoneA == null || zoneA.isEmpty() || zoneB == null || zoneB.isEmpty()) {
            return false;
        }
        if (zoneA.equals(zoneB)) {
            return false;
        }
        TimeZone a = TimeZone.getTimeZone(zoneA);
        TimeZone b = TimeZone.getTimeZone(zoneB);
        return a.getOffset(atEpochMs) != b.getOffset(atEpochMs);
    }
}
