package com.walter.claudewilson.model;

import java.util.Random;

/**
 * Derives a variant of a device profile instead of only picking one out of a table.
 *
 * <p>The identity fields (brand, product, device, release, hardware, board) are kept as they are;
 * the per-build fields are regenerated with the same shape as the template, and the fingerprint is
 * then rebuilt from those fields. Keeping the shape matters: a Samsung incremental stays
 * letter-and-digit and a Pixel incremental stays numeric, so the result still looks like a real
 * build of that model rather than a random string.
 *
 * <p>This is a pure function of the profile and a seed -- no clock, no device access -- so the same
 * seed always yields the same variant and the result can be asserted in a test (in particular:
 * it passes {@code FingerprintCoherenceProbe}).
 */
public final class PersonaSynth {

    private static final String DIGITS = "0123456789";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
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
        out[13] = patch(device[13], random);
        // Derived from this row's own identity, never edited from a template string: a row whose
        // stored fingerprint belonged to another product used to leak that product into the build.
        out[8] = fingerprint(device[2], device[5], device[4], device[11], out[9], out[10]);
        return out;
    }

    /** Keeps the leading characters and regenerates the tail with the template's character set. */
    static String vary(String template, Random random) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        boolean numeric = template.matches("\\d+");
        String charset = numeric ? DIGITS : ALNUM;
        int keep = Math.max(0, template.length() - VARY_TAIL);
        StringBuilder out = new StringBuilder(template.substring(0, keep));
        for (int i = keep; i < template.length(); i++) {
            char original = template.charAt(i);
            if (!Character.isLetterOrDigit(original)) {
                out.append(original);
                continue;
            }
            char next = charset.charAt(random.nextInt(charset.length()));
            // A numeric field must not gain a leading zero.
            if (numeric && out.length() == 0 && next == '0') {
                next = '1';
            }
            out.append(next);
        }
        return out.toString();
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
        return String.format("%04d-%02d-01", year, month);
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
}
