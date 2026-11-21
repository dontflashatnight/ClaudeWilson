package com.walter.claudewilson.model;

/**
 * Immutable snapshot of one Android user space's bound persona and region configuration.
 *
 * <p>Keeping region selection bound to each space (instead of one global slot) ensures switching
 * between Android users atomically switches both identity overrides and region settings.
 */
public final class SpaceProfile {
    public final String userId;
    public final String regionIso;
    public final String applyZone;
    public final String applyLocale;
    public final boolean keepRealSim;
    public final String carrierOverride;
    public final String opOverride;
    public final int deviceIndex;
    public final boolean hasDeviceOverride;
    public final String androidId;
    public final boolean hasAidOverride;
    public final String cscOverride;
    public final String countryHint;
    public final boolean locationEnabled;
    public final String ipv6Prefix;

    public SpaceProfile(String userId, String regionIso, String applyZone, String applyLocale,
                        boolean keepRealSim, String carrierOverride, String opOverride,
                        int deviceIndex, boolean hasDeviceOverride, String androidId,
                        boolean hasAidOverride, String cscOverride, String countryHint,
                        boolean locationEnabled, String ipv6Prefix) {
        this.userId = userId == null || userId.isEmpty() ? "0" : userId;
        this.regionIso = regionIso == null ? "" : regionIso;
        this.applyZone = applyZone == null ? "" : applyZone;
        this.applyLocale = applyLocale == null ? "" : applyLocale;
        this.keepRealSim = keepRealSim;
        this.carrierOverride = carrierOverride == null ? "" : carrierOverride;
        this.opOverride = opOverride == null ? "" : opOverride;
        this.deviceIndex = deviceIndex;
        this.hasDeviceOverride = hasDeviceOverride;
        this.androidId = androidId == null ? "" : androidId;
        this.hasAidOverride = hasAidOverride;
        this.cscOverride = cscOverride == null ? "" : cscOverride;
        this.countryHint = countryHint == null ? "" : countryHint;
        this.locationEnabled = locationEnabled;
        this.ipv6Prefix = ipv6Prefix == null ? "" : ipv6Prefix;
    }

    public boolean hasRegion() {
        return !regionIso.isEmpty();
    }
}
