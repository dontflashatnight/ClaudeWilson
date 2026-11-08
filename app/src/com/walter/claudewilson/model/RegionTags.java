package com.walter.claudewilson.model;

/**
 * Splits a hardware-tag table by region.
 *
 * <p>Pure on purpose: the table is passed in, there is no locale and no device access, so the
 * classification (which tags name a region, which are generic market suffixes) can be asserted
 * without a phone. The caller owns the wording.
 */
public final class RegionTags {

    private RegionTags() {
    }

    /**
     * @param table rows of {@code {tag, iso}}; a tag starting with {@code '_'} is a generic
     *              market suffix rather than a vendor tag
     * @return {@code {vendor tags, generic tags}} for that iso, joined with {@code '/'}, empty
     *         strings when the region has none
     */
    public static String[] split(String iso, String[][] table) {
        StringBuilder vendor = new StringBuilder();
        StringBuilder generic = new StringBuilder();
        if (table != null) {
            for (String[] row : table) {
                if (row == null || row.length < 2 || !iso.equals(row[1])) {
                    continue;
                }
                StringBuilder sink = row[0].startsWith("_") ? generic : vendor;
                if (sink.length() > 0) {
                    sink.append('/');
                }
                sink.append(row[0]);
            }
        }
        return new String[]{vendor.toString(), generic.toString()};
    }
}
