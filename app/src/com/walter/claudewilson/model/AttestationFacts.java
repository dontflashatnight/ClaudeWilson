package com.walter.claudewilson.model;

/**
 * Result of one hardware-key attestation attempt, as plain data.
 *
 * <p>Two different "no" answers are kept apart: {@link #NOT_COLLECTED} means nobody tried, while
 * {@link #failed} means the key could not be generated and carries the reason. Only the second
 * one is an observation about the device.
 */
public final class AttestationFacts {
    public static final AttestationFacts NOT_COLLECTED =
            new AttestationFacts(false, false, "", -1, false, false, -1, -1, "", "", "", "");

    private final boolean collected;
    private final boolean available;
    private final String securityLevel;
    private final int verifiedBoot;
    private final boolean rootOfTrust;
    private final boolean deviceLocked;
    private final int osVersion;
    private final long osPatchLevel;
    private final String teeTags;
    private final String deviceProps;
    private final String challenge;
    private final String error;

    public AttestationFacts(boolean collected, boolean available, String securityLevel,
                            int verifiedBoot, boolean rootOfTrust, boolean deviceLocked,
                            int osVersion, long osPatchLevel, String teeTags, String deviceProps,
                            String challenge, String error) {
        this.collected = collected;
        this.available = available;
        this.securityLevel = nz(securityLevel);
        this.verifiedBoot = verifiedBoot;
        this.rootOfTrust = rootOfTrust;
        this.deviceLocked = deviceLocked;
        this.osVersion = osVersion;
        this.osPatchLevel = osPatchLevel;
        this.teeTags = nz(teeTags);
        this.deviceProps = nz(deviceProps);
        this.challenge = nz(challenge);
        this.error = nz(error);
    }

    public static AttestationFacts failed(String error) {
        return new AttestationFacts(true, false, "", -1, false, false, -1, -1, "", "", "", error);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** True when the attempt was made at all. */
    public boolean collected() {
        return collected;
    }

    /** True when a key was generated and the attestation record was parsed. */
    public boolean available() {
        return available;
    }

    public String securityLevel() {
        return securityLevel;
    }

    public int verifiedBoot() {
        return verifiedBoot;
    }

    /** True when the TEE supplied a root-of-trust block; without it, "locked" cannot be claimed. */
    public boolean rootOfTrust() {
        return rootOfTrust;
    }

    public boolean deviceLocked() {
        return deviceLocked;
    }

    public int osVersion() {
        return osVersion;
    }

    public long osPatchLevel() {
        return osPatchLevel;
    }

    public String teeTags() {
        return teeTags;
    }

    /**
     * Rendered attestation-id declarations, e.g. {@code brand=Google model=husky serial=***1234}.
     * Entries the TEE did not claim carry a {@code (sw)} suffix — only those can be overridden
     * by an injection layer; TEE-claimed values are hardware truth a server can cross-check.
     */
    public String deviceProps() {
        return deviceProps;
    }

    public String challenge() {
        return challenge;
    }

    public String error() {
        return error;
    }

    public boolean softwareLevel() {
        return "software".equals(securityLevel);
    }
}
