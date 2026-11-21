package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.Random;

/**
 * Derives a variant of a device profile instead of only picking one out of a table.
 *
 * <p>The identity fields (brand, product, device, release, hardware, board) are kept as they are;
 * the per-build fields are regenerated with the same character classes as the template, and the
 * fingerprint is rebuilt from those fields. Keeping each position's character class matters: AOSP
 * sequence suffixes ({@code .015}) stay numeric, lowercase hex suffixes ({@code R.1e31d5}) stay
 * lowercase hex, and uppercase letters stay uppercase.
 *
 * <p>This is a pure function of the profile and a seed -- no clock, no device access -- so the same
 * seed always yields the same variant and the result can be asserted in a test (in particular:
 * it passes {@code FingerprintCoherenceProbe}).
 */
public final class PersonaSynth {

    private static final String DIGITS = "0123456789";
    private static final String UPPER_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER_HEX = "abcdef";
    private static final String LOWER_ALPHA = "abcdefghijklmnopqrstuvwxyz";
    /** How many trailing characters of a build field are treated as the varying part. */
    private static final int VARY_TAIL = 4;

    private PersonaSynth() {
    }

    /**
     * Returns a copy of {@code device} (14 fields, see PersonaPanel.DEV) with ID, incremental,
     * security patch and fingerprint regenerated.
     */
    public static String[] cross(String[] device, long seed) {
        if (device == null || device.length < 14) {
            return device;
        }
        Random random = new Random(seed);
        String[] out = device.clone();
        out[9] = vary(device[9], random);
        out[10] = vary(device[10], random);
        String idPatch = securityPatchFromBuildId(out[9]);
        out[13] = idPatch != null ? idPatch : patch(device[13], random);
        // Derived from this row's own identity, never edited from a template string: a row whose
        // stored fingerprint belonged to another product used to leak that product into the build.
        out[8] = fingerprint(device[2], device[5], device[4], device[11], out[9], out[10]);
        return out;
    }

    /**
     * Anchors a 14-field device profile to the real host Android generation ({@code hostSdkInt} /
     * {@code hostRelease}) so target processes never observe a spoofed {@code SDK_INT} that
     * disagrees with host ART/Bionic APIs or {@code SDK_INT_FULL}.
     *
     * <p>When the template's SDK differs from {@code hostSdkInt}, the build ID branch/date and
     * security patch are remapped together from the same year/month to match the host generation
     * and the fingerprint is rebuilt from the aligned fields.
     */
    public static String[] alignToHostSdk(String[] device, int hostSdkInt, String hostRelease,
                                          long seed) {
        if (device == null || device.length < 14 || hostSdkInt <= 0) {
            return device;
        }
        String[] out = device.clone();
        String targetRelease = (hostRelease != null && !hostRelease.trim().isEmpty())
                ? hostRelease.trim()
                : releaseForSdk(hostSdkInt);
        int templateSdk = parseSdk(device[12]);
        out[11] = targetRelease;
        out[12] = String.valueOf(hostSdkInt);
        if (templateSdk != hostSdkInt) {
            Random random = new Random(seed ^ 0x5A17B00DL);
            int[] ym = yearMonthForSdk(hostSdkInt, random);
            out[9] = remapBuildId(out[9], hostSdkInt, ym, random);
            out[13] = formatSecurityPatch(ym);
        }
        out[8] = fingerprint(out[2], out[5], out[4], out[11], out[9], out[10]);
        return out;
    }

    /** Canonical Android release string for an API level (33..37). */
    public static String releaseForSdk(int sdkInt) {
        switch (sdkInt) {
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            case 37: return "17";
            default: return String.valueOf(Math.max(13, sdkInt - 20));
        }
    }

    /** Maps an Android release string ("13".."17") to its API level, or -1 if unsupported. */
    public static int sdkForRelease(String release) {
        if (release == null) {
            return -1;
        }
        switch (release.trim()) {
            case "13": return 33;
            case "14": return 34;
            case "15": return 35;
            case "16": return 36;
            case "17": return 37;
            default: return -1;
        }
    }

    /** Canonical AOSP release build-ID prefix for API 33..37. */
    public static String buildPrefixForSdk(int sdkInt) {
        if (sdkInt <= 33) return "TQ3A";
        if (sdkInt == 34) return "AP2A";
        if (sdkInt == 35) return "AP3A";
        if (sdkInt == 36) return "BP3A";
        return "CP1A";
    }

    /** Generates a coherent pair {@code [buildId, securityPatch]} from the same year/month. */
    public static String[] buildIdAndPatchForSdk(int sdkInt, Random random) {
        int[] ym = yearMonthForSdk(sdkInt, random);
        return new String[]{formatBuildId(sdkInt, ym, random), formatSecurityPatch(ym)};
    }

    /** Generates a realistic AOSP build ID ({@code PREFIX.YYMMDD.NNN}) for API 33..37. */
    public static String syntheticBuildIdForSdk(int sdkInt, Random random) {
        return formatBuildId(sdkInt, yearMonthForSdk(sdkInt, random), random);
    }

    /** Generates a realistic security patch date ({@code YYYY-MM-05}) for API 33..37. */
    public static String securityPatchForSdk(int sdkInt, Random random) {
        return formatSecurityPatch(yearMonthForSdk(sdkInt, random));
    }

    private static String formatBuildId(int sdkInt, int[] ym, Random random) {
        int seq = 1 + random.nextInt(49);
        return String.format(Locale.US, "%s.%02d%02d05.%03d",
                buildPrefixForSdk(sdkInt), ym[0] % 100, ym[1], seq);
    }

    private static String formatSecurityPatch(int[] ym) {
        return String.format(Locale.US, "%04d-%02d-05", ym[0], ym[1]);
    }

    private static String securityPatchFromBuildId(String id) {
        if (id == null || !id.matches("[A-Z0-9]{4}\\.\\d{6}\\.\\d{3}(?:\\.[A-Za-z0-9]+)?")) {
            return null;
        }
        int yy = Integer.parseInt(id.substring(5, 7));
        int mm = Integer.parseInt(id.substring(7, 9));
        if (mm < 1 || mm > 12) {
            return null;
        }
        return String.format(Locale.US, "20%02d-%02d-05", yy, mm);
    }

    private static int[] yearMonthForSdk(int sdkInt, Random random) {
        int baseYear;
        int baseMonth;
        int span;
        if (sdkInt <= 33) {
            baseYear = 2023;
            baseMonth = 1;
            span = 9; // 2023-01 .. 2023-09
        } else if (sdkInt == 34) {
            baseYear = 2024;
            baseMonth = 1;
            span = 8; // 2024-01 .. 2024-08
        } else if (sdkInt == 35) {
            baseYear = 2024;
            baseMonth = 10;
            span = 8; // 2024-10 .. 2025-05
        } else if (sdkInt == 36) {
            baseYear = 2025;
            baseMonth = 6;
            span = 11; // 2025-06 .. 2026-04
        } else {
            baseYear = 2026;
            baseMonth = 6;
            span = 6; // 2026-06 .. 2026-11
        }
        int total = (baseYear * 12 + (baseMonth - 1)) + random.nextInt(span);
        return new int[]{total / 12, (total % 12) + 1};
    }

    private static String remapBuildId(String id, int hostSdkInt, int[] ym, Random random) {
        if (id == null || id.isEmpty()) {
            return formatBuildId(hostSdkInt, ym, random);
        }
        // Standard AOSP or OEM-prefixed dotted build ID (e.g. AP3A.241005.015,
        // UP1A.231005.007, UKQ1.230804.001, AP3A.240905.015.A2).
        if (id.matches("[A-Z0-9]{4}\\.\\d{6}\\.\\d{3}(?:\\.[A-Za-z0-9]+)?")) {
            String base = formatBuildId(hostSdkInt, ym, random);
            int thirdDot = id.indexOf('.', 12);
            return thirdDot > 0 ? base + id.substring(thirdDot) : base;
        }
        // Non-AOSP vendor IDs (e.g. OxygenOS CPH2653_15.0.0.401): keep structure intact.
        return id;
    }

    private static int parseSdk(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Keeps the leading characters and regenerates the tail with per-character isomorphic classes:
     * digits stay digits, uppercase letters stay uppercase, lowercase hex stays lowercase hex,
     * other lowercase letters stay lowercase, and punctuation is preserved verbatim.
     */
    static String vary(String template, Random random) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        boolean numeric = template.matches("\\d+");
        boolean lowercaseHexOnly = hasOnlyHexLowercase(template);
        int keep = Math.max(0, template.length() - VARY_TAIL);
        StringBuilder out = new StringBuilder(template.substring(0, keep));
        for (int i = keep; i < template.length(); i++) {
            char original = template.charAt(i);
            if (original >= '0' && original <= '9') {
                char next = DIGITS.charAt(random.nextInt(DIGITS.length()));
                // A pure numeric field must not gain a leading zero.
                if (numeric && out.length() == 0 && next == '0') {
                    next = '1';
                }
                out.append(next);
            } else if (original >= 'A' && original <= 'Z') {
                out.append(UPPER_ALPHA.charAt(random.nextInt(UPPER_ALPHA.length())));
            } else if (original >= 'a' && original <= 'z') {
                String pool = (lowercaseHexOnly && original <= 'f') ? LOWER_HEX : LOWER_ALPHA;
                out.append(pool.charAt(random.nextInt(pool.length())));
            } else {
                out.append(original);
            }
        }
        return out.toString();
    }

    private static boolean hasOnlyHexLowercase(String s) {
        boolean sawLower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sawLower = true;
                if (c > 'f') {
                    return false;
                }
            }
        }
        return sawLower;
    }

    /** Moves the security patch a few months back from the template, on the first of the month. */
    static String patch(String template, Random random) {
        if (template == null || !template.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return template;
        }
        int year = Integer.parseInt(template.substring(0, 4));
        int month = Integer.parseInt(template.substring(5, 7));
        int back = random.nextInt(12);
        month -= back;
        while (month < 1) {
            month += 12;
            year--;
        }
        return String.format(Locale.US, "%04d-%02d-01", year, month);
    }

    /**
     * The canonical build fingerprint of an identity: {@code brand/product/device:release/id/
     * incremental:user/release-keys}.
     *
     * <p>Single source for both layers -- the injected process and the system properties -- so the
     * two can never disagree, and no segment can come from a different device.
     */
    public static String fingerprint(String brand, String product, String device,
                                     String release, String buildId, String incremental) {
        if (brand == null || product == null || device == null || release == null
                || buildId == null || incremental == null) {
            return null;
        }
        return brand + "/" + product + "/" + device + ":" + release + "/" + buildId
                + "/" + incremental + ":user/release-keys";
    }

    /**
     * Derives coherent {@code {SOC_MANUFACTURER, SOC_MODEL, SKU}} values for a device profile so
     * {@code Build.SOC_MANUFACTURER}, {@code Build.SOC_MODEL}, {@code Build.SKU}, and the matching
     * {@code ro.soc.*} / {@code ro.boot.hardware.sku} properties agree with the spoofed device.
     */
    public static String[] socFor(String manufacturer, String model, String device,
                                  String hardware, String board) {
        String mfr = manufacturer == null ? "" : manufacturer.trim();
        String mdl = model == null ? "" : model.trim();
        String dev = device == null ? "" : device.trim().toLowerCase(Locale.ROOT);
        String hw = hardware == null ? "" : hardware.trim().toLowerCase(Locale.ROOT);
        String brd = board == null ? "" : board.trim().toLowerCase(Locale.ROOT);

        if ("google".equalsIgnoreCase(mfr)) {
            if ("lynx".equals(dev)) {
                return new String[]{"Google", "Tensor G2", "GHL1X"};
            }
            if ("husky".equals(dev)) {
                return new String[]{"Google", "Tensor G3", "GC3VE"};
            }
            if ("akita".equals(dev)) {
                return new String[]{"Google", "Tensor G3", "GKV4X"};
            }
            if ("tokay".equals(dev)) {
                return new String[]{"Google", "Tensor G4", "G2YBB"};
            }
            if ("caiman".equals(dev)) {
                return new String[]{"Google", "Tensor G4", "GR83Y"};
            }
            if ("komodo".equals(dev)) {
                return new String[]{"Google", "Tensor G4", "GGX8B"};
            }
            if ("comet".equals(dev)) {
                return new String[]{"Google", "Tensor G4", "GC15S"};
            }
            if ("tegu".equals(dev)) {
                return new String[]{"Google", "Tensor G4", "GXQ96"};
            }
            if ("blazer".equals(dev)) {
                return new String[]{"Google", "Tensor G5", "GEHN3"};
            }
            return new String[]{"Google", "Tensor", mdl.isEmpty() ? dev : mdl};
        }
        if ("sun".equals(brd)) {
            return new String[]{"QTI", "SM8750", mdl};
        }
        if ("kalama".equals(brd)) {
            String soc = mdl.toUpperCase(Locale.ROOT).startsWith("SM-S918") ? "SM8550" : "SM8650";
            return new String[]{"QTI", soc, mdl};
        }
        if ("s5e8845".equals(hw) || "erd8845".equals(brd)) {
            return new String[]{"Samsung", "Exynos 1480", mdl};
        }
        if (hw.startsWith("mt")) {
            return new String[]{"Mediatek", hw.toUpperCase(Locale.ROOT), mdl};
        }
        if ("qcom".equals(hw) || hw.startsWith("qcom")) {
            String soc = brd.isEmpty() ? "QCOM" : brd.toUpperCase(Locale.ROOT);
            return new String[]{"QTI", soc, mdl};
        }
        String fallbackMfr = mfr.isEmpty() ? "QTI" : mfr;
        String fallbackSoc = !brd.isEmpty() ? brd.toUpperCase(Locale.ROOT)
                : (!hw.isEmpty() ? hw.toUpperCase(Locale.ROOT) : "UNKNOWN");
        return new String[]{fallbackMfr, fallbackSoc, mdl};
    }

    /**
     * Derives a deterministic RFC 4122 UUIDv4 advertising identifier from the persona's seed
     * (typically {@code androidId}) so switching personas produces a distinct, stable ad ID.
     */
    public static String advertisingId(String seed) {
        if (seed == null || seed.trim().isEmpty()) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("cw-adid:" + seed.trim())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0F) | 0x40);
            h[8] = (byte) ((h[8] & 0x3F) | 0x80);
            return String.format(Locale.US,
                    "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                    h[0] & 0xFF, h[1] & 0xFF, h[2] & 0xFF, h[3] & 0xFF,
                    h[4] & 0xFF, h[5] & 0xFF,
                    h[6] & 0xFF, h[7] & 0xFF,
                    h[8] & 0xFF, h[9] & 0xFF,
                    h[10] & 0xFF, h[11] & 0xFF, h[12] & 0xFF, h[13] & 0xFF,
                    h[14] & 0xFF, h[15] & 0xFF);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
