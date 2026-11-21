package com.walter.claudewilson.model;

import java.util.Locale;

/**
 * Deterministic, layered salt derivation and state-restoration rules for persona spaces.
 *
 * <p>Salts form a strict hierarchy:
 * <ol>
 *   <li>{@code spaceSeed}: persistent non-zero SecureRandom seed per Android user space.</li>
 *   <li>{@code deviceCrossSeed}: mixes {@code spaceSeed} with any explicit model-pick seed so
 *       two spaces on the same/default device model never share {@code seed = 0L}.</li>
 *   <li>{@code profileSeed}: mixes {@code deviceCrossSeed}, the per-(space, modelIdentity)
 *       {@code profileSalt}, and the normalized {@code regionIso}, isolating sensor biases,
 *       GPS drift, Wi-Fi, BLE, and IPv6 across both spaces and regions.</li>
 *   <li>{@code wifiSeed} / {@code bleSeed}: domain-separated radio neighbourhood seeds.</li>
 * </ol>
 */
public final class PersonaSalt {

    /** Legacy v1 salt derivation preserved verbatim for already-used spaces on upgrade. */
    public static final int SALT_V1 = 1;
    /** Layered v2 salt derivation (SecureRandom spaceSeed + region isolation) for fresh spaces. */
    public static final int SALT_V2 = 2;

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX_MODEL = 0x517CC1B727220A95L;
    private static final long MIX_WIFI = 0x243F6A8885A308D3L;
    private static final long MIX_BLE = 0x13198A2E03707344L;
    private static final long BLE_STEP = 0xBF58476D1CE4E5B9L;
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private PersonaSalt() {
    }

    /** SplitMix64 avalanche finalizer. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Ensures a persisted or derived salt is never zero. */
    public static long nonZero(long value) {
        return value == 0L ? GOLDEN_GAMMA : value;
    }

    /** Deterministic 64-bit hash for short domain/region labels (case-insensitive). */
    public static long hashText(String text) {
        long h = FNV_OFFSET;
        if (text != null) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < normalized.length(); i++) {
                h ^= normalized.charAt(i);
                h *= FNV_PRIME;
            }
        }
        return mix64(h);
    }

    /**
     * Resolves the salt version for a space: an already-used space upgrading from v1 stays pinned
     * to {@link #SALT_V1} so its established fingerprint/sensor/radio identity never mutates, while
     * a brand-new space opts into {@link #SALT_V2}.
     */
    public static int resolveSaltVersion(int persistedVersion, boolean hasLegacyState) {
        if (persistedVersion == SALT_V1 || persistedVersion == SALT_V2) {
            return persistedVersion;
        }
        return hasLegacyState ? SALT_V1 : SALT_V2;
    }

    /**
     * Version-aware seed passed to {@link PersonaSynth#cross(String[], long)}.
     *
     * <p>v1 spaces preserve exact legacy {@code modelSeed} derivation; v2 spaces mix the per-space
     * {@code spaceSeed} so two fresh spaces on the default model never collide on {@code 0L}.
     */
    public static long deviceCrossSeed(int saltVersion, long spaceSeed, long modelSeed) {
        if (saltVersion <= SALT_V1) {
            return modelSeed;
        }
        return deviceCrossSeed(spaceSeed, modelSeed);
    }

    /**
     * Derives the v2 seed passed to {@link PersonaSynth#cross(String[], long)}.
     *
     * <p>Even when {@code modelSeed == 0L} (default model before any manual pick), mixing the
     * per-space {@code spaceSeed} ensures distinct spaces never share identical build variants.
     */
    public static long deviceCrossSeed(long spaceSeed, long modelSeed) {
        long base = nonZero(spaceSeed);
        return nonZero(mix64(base ^ mix64(modelSeed + GOLDEN_GAMMA)));
    }

    /**
     * Version-aware per-profile seed: v1 preserves legacy {@code crossSeed ^ profileSalt} so
     * existing spaces keep stable sensor/radio salts across upgrades; v2 mixes {@code regionIso}.
     */
    public static long profileSeed(int saltVersion, long deviceCrossSeed, long profileSalt,
                                   String regionIso) {
        if (saltVersion <= SALT_V1) {
            return deviceCrossSeed ^ profileSalt;
        }
        return profileSeed(deviceCrossSeed, profileSalt, regionIso);
    }

    /**
     * Derives the v2 per-(space, model, region) profile seed used by build alignment, sensors,
     * GPS drift, IPv6 prefix selection, Wi-Fi, and BLE.
     */
    public static long profileSeed(long deviceCrossSeed, long profileSalt, String regionIso) {
        long h = mix64(nonZero(deviceCrossSeed) ^ mix64(nonZero(profileSalt) + MIX_MODEL));
        if (regionIso != null && !regionIso.trim().isEmpty()) {
            h = mix64(h ^ hashText("region:" + regionIso));
        }
        return nonZero(h);
    }

    /** Version-aware Wi-Fi neighbourhood seed. */
    public static long wifiSeed(int saltVersion, long profileSeed, long wifiSalt) {
        if (saltVersion <= SALT_V1) {
            return profileSeed ^ (wifiSalt * GOLDEN_GAMMA);
        }
        return wifiSeed(profileSeed, wifiSalt);
    }

    /** Domain-separated v2 seed for Wi-Fi neighbourhood synthesis. */
    public static long wifiSeed(long profileSeed, long wifiSalt) {
        return nonZero(mix64(nonZero(profileSeed) ^ mix64(wifiSalt * GOLDEN_GAMMA + MIX_WIFI)));
    }

    /** Version-aware BLE neighbourhood seed. */
    public static long bleSeed(int saltVersion, long profileSeed, long bleSalt) {
        if (saltVersion <= SALT_V1) {
            return profileSeed ^ (bleSalt * BLE_STEP);
        }
        return bleSeed(profileSeed, bleSalt);
    }

    /** Domain-separated v2 seed for BLE neighbourhood synthesis. */
    public static long bleSeed(long profileSeed, long bleSalt) {
        return nonZero(mix64(nonZero(profileSeed) ^ mix64(bleSalt * BLE_STEP + MIX_BLE)));
    }

    /** Extracts the {@code VERSION.INCREMENTAL} segment from a standard Android fingerprint. */
    public static String extractIncrementalFromFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            return "";
        }
        String[] colon = fingerprint.trim().split(":");
        if (colon.length != 3) {
            return "";
        }
        String[] mid = colon[1].split("/");
        return mid.length == 3 ? mid[2].trim() : "";
    }

    /**
     * Applies a frozen/adopted build pin {@code (fingerprint, id, incremental, patch)} onto a
     * 14-field device row when the pin belongs to the exact same {@code brand/product/device} and
     * Android {@code release}; returns null if the pin does not match this model/release.
     */
    public static String[] applyBuildPin(String[] baseRow, String pinnedFingerprint,
                                         String pinnedId, String pinnedIncremental,
                                         String pinnedPatch) {
        if (baseRow == null || baseRow.length < 14
                || pinnedFingerprint == null || pinnedFingerprint.trim().isEmpty()
                || pinnedId == null || pinnedId.trim().isEmpty()) {
            return null;
        }
        String fp = pinnedFingerprint.trim();
        String id = pinnedId.trim();
        String[] colon = fp.split(":");
        if (colon.length != 3) {
            return null;
        }
        String[] left = colon[0].split("/");
        String[] mid = colon[1].split("/");
        if (left.length != 3 || mid.length != 3) {
            return null;
        }
        String brand = baseRow[2] == null ? "" : baseRow[2].trim();
        String product = baseRow[5] == null ? "" : baseRow[5].trim();
        String device = baseRow[4] == null ? "" : baseRow[4].trim();
        String release = baseRow[11] == null ? "" : baseRow[11].trim();
        if (!left[0].trim().equalsIgnoreCase(brand)
                || !left[1].trim().equalsIgnoreCase(product)
                || !left[2].trim().equalsIgnoreCase(device)
                || !mid[0].trim().equalsIgnoreCase(release)
                || !mid[1].trim().equals(id)) {
            return null;
        }
        String effInc = (pinnedIncremental != null && !pinnedIncremental.trim().isEmpty())
                ? pinnedIncremental.trim() : mid[2].trim();
        if (effInc.isEmpty()) {
            return null;
        }
        String effPatch = (pinnedPatch != null && !pinnedPatch.trim().isEmpty())
                ? pinnedPatch.trim() : derivePatchFromBuildId(id, baseRow[13]);
        String[] out = baseRow.clone();
        out[8] = fp;
        out[9] = id;
        out[10] = effInc;
        out[13] = effPatch;
        return out;
    }

    private static String derivePatchFromBuildId(String id, String fallback) {
        if (id != null && id.matches("[A-Z0-9]{4}\\.\\d{6}\\.[A-Za-z0-9.]+")) {
            int yy = Integer.parseInt(id.substring(5, 7));
            int mm = Integer.parseInt(id.substring(7, 9));
            if (mm >= 1 && mm <= 12) {
                return String.format(Locale.US, "20%02d-%02d-05", yy, mm);
            }
        }
        return fallback == null ? "" : fallback;
    }

    /** Deterministic seed for initial zone/locale/carrier pick of a (space, region) snapshot. */
    public static long regionPickSeed(long spaceSeed, String regionIso) {
        return nonZero(mix64(nonZero(spaceSeed) ^ hashText("pick:" + (regionIso == null ? "" : regionIso))));
    }

    /**
     * Decides whether a legacy global {@code EnvSession} region should be migrated into the
     * current space.
     *
     * <p>Migration is one-shot and forbidden once any space already holds a bound region, so an
     * unconfigured Native space never inherits another space's active session.
     */
    public static boolean shouldMigrateLegacyRegion(boolean alreadyMigrated,
                                                    boolean anySpaceHasRegion,
                                                    String currentSpaceIso,
                                                    String legacyActiveIso) {
        if (alreadyMigrated || anySpaceHasRegion) {
            return false;
        }
        if (currentSpaceIso != null && !currentSpaceIso.trim().isEmpty()) {
            return false;
        }
        return legacyActiveIso != null && !legacyActiveIso.trim().isEmpty();
    }

    /**
     * Evaluates whether the system-layer applied region belongs to {@code spaceUser} and matches
     * {@code spaceIso}.
     */
    public static boolean isRegionAppliedForSpace(String spaceUser, String spaceIso,
                                                  String activeUser, String activeIso,
                                                  boolean activeApplied, boolean isPartial) {
        if (!activeApplied || isPartial) {
            return false;
        }
        if (spaceIso == null || spaceIso.trim().isEmpty()
                || activeIso == null || !spaceIso.trim().equalsIgnoreCase(activeIso.trim())) {
            return false;
        }
        if (activeUser != null && !activeUser.trim().isEmpty()) {
            return activeUser.trim().equals(spaceUser == null ? "" : spaceUser.trim());
        }
        return true;
    }

    /**
     * Returns true only when a restored space zone actually differs from the persisted region
     * snapshot's zone, preventing unnecessary writes that could disturb saved coordinates.
     */
    public static boolean shouldUpdateRestoredZone(String requestedZone, String savedZone) {
        if (requestedZone == null || requestedZone.trim().isEmpty()) {
            return false;
        }
        return !requestedZone.trim().equals(savedZone == null ? "" : savedZone.trim());
    }
}
