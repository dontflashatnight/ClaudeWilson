package com.walter.claudewilson.model;

/**
 * Parsed build fingerprint: {@code brand/product/device:release/id/incremental:type/tags}.
 *
 * <p>Parsing is a pure function so the coherence rules can be tested with real device strings
 * without a device.
 */
public final class Fp {
    private final String brand;
    private final String product;
    private final String device;
    private final String release;
    private final String buildId;
    private final String incremental;
    private final String type;
    private final String tags;

    private Fp(String[] p) {
        this.brand = p[0];
        this.product = p[1];
        this.device = p[2];
        this.release = p[3];
        this.buildId = p[4];
        this.incremental = p[5];
        this.type = p[6];
        this.tags = p[7];
    }

    /** Returns null when the string is not a fingerprint; callers report that as unknown. */
    public static Fp parse(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        String[] parts = fingerprint.split("[/:]");
        if (parts.length < 8) {
            return null;
        }
        String[] head = new String[8];
        System.arraycopy(parts, 0, head, 0, 8);
        return new Fp(head);
    }

    public String brand() {
        return brand;
    }

    public String product() {
        return product;
    }

    public String device() {
        return device;
    }

    public String release() {
        return release;
    }

    public String buildId() {
        return buildId;
    }

    public String incremental() {
        return incremental;
    }

    public String type() {
        return type;
    }

    public String tags() {
        return tags;
    }
}
