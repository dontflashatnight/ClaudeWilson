package com.walter.claudewilson;

/** Process-local selection survives recreation caused by set-app-locales. */
final class RegionSelection {
    private RegionSelection() {}
    static volatile int regionIdx = -1;
    static volatile String applyZone, applyLocale;
    /** True only after the last apply reported success; reset when the selection changes. */
    static volatile boolean regionApplied;
}
