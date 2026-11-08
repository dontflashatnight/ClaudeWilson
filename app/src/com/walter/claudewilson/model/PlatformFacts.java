package com.walter.claudewilson.model;

/** Platform-side facts: attestation, key hardware, biometrics and the Google stack. */
public final class PlatformFacts {
    public static final PlatformFacts EMPTY = new PlatformFacts(
            false, AttestationFacts.NOT_COLLECTED, false, -1, false, false, false,
            Text.unreadable(), Text.unreadable(), false);

    private final boolean collected;
    private final AttestationFacts attestation;
    private final boolean strongbox;
    private final int biometricStatus;
    private final boolean deviceSecure;
    private final boolean packagesChecked;
    private final boolean playStorePresent;
    private final Text playStoreVersion;
    private final Text gmsVersion;
    private final boolean revancedGms;

    public PlatformFacts(boolean collected, AttestationFacts attestation, boolean strongbox,
                         int biometricStatus, boolean deviceSecure, boolean packagesChecked,
                         boolean playStorePresent, Text playStoreVersion, Text gmsVersion,
                         boolean revancedGms) {
        this.collected = collected;
        this.attestation = attestation == null ? AttestationFacts.NOT_COLLECTED : attestation;
        this.strongbox = strongbox;
        this.biometricStatus = biometricStatus;
        this.deviceSecure = deviceSecure;
        this.packagesChecked = packagesChecked;
        this.playStorePresent = playStorePresent;
        this.playStoreVersion = playStoreVersion == null ? Text.unreadable() : playStoreVersion;
        this.gmsVersion = gmsVersion == null ? Text.unreadable() : gmsVersion;
        this.revancedGms = revancedGms;
    }

    /** False when the platform facts were never collected, which is not a clean device. */
    public boolean collected() {
        return collected;
    }

    public AttestationFacts attestation() {
        return attestation;
    }

    public boolean strongbox() {
        return strongbox;
    }

    /** Raw `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` code; 0 means success. */
    public int biometricStatus() {
        return biometricStatus;
    }

    public boolean deviceSecure() {
        return deviceSecure;
    }

    /** True when the store/GMS package queries actually ran. */
    public boolean packagesChecked() {
        return packagesChecked;
    }

    public boolean playStorePresent() {
        return playStorePresent;
    }

    public Text playStoreVersion() {
        return playStoreVersion;
    }

    public Text gmsVersion() {
        return gmsVersion;
    }

    public boolean revancedGms() {
        return revancedGms;
    }

    /**
     * A reimplemented Google stack advertises itself with a 0.x version on the store or on GMS.
     * Kept here so the GMS check and the verdict check cannot drift apart.
     */
    public boolean microg() {
        return playStoreVersion.value().startsWith("0.")
                || gmsVersion.value().startsWith("0.");
    }
}
