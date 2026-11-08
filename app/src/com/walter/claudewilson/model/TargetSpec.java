package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/**
 * One application this tool can inspect.
 *
 * <p>Every target-specific literal lives here or in {@link TargetRegistry}; the UI and the
 * shared environment engine consume this description instead of branching on a package name.
 * A target without hooks stays readable: diagnostics are useful before any write path exists.
 */
public final class TargetSpec {
    /** Read-only inspection only. No process hooks, no config file, no writes. */
    public static final int SUPPORT_DIAGNOSTIC = 1;
    /** Shared environment hooks; region/persona writes allowed. */
    public static final int SUPPORT_ENVIRONMENT = 2;
    /** Environment plus local identity/data operations (SSAID, app data, per-app ID). */
    public static final int SUPPORT_FULL = 3;

    private final String id;
    private final String packageName;
    private final String displayName;
    private final String marketUri;
    private final String domainRule;
    private final int support;
    private final String legacyConfigPath;
    private final List<String> identityMarkers;
    private final List<String> integrityMarkers;

    public TargetSpec(String id, String packageName, String displayName, String marketUri,
                      String domainRule, int support, String legacyConfigPath) {
        this(id, packageName, displayName, marketUri, domainRule, support, legacyConfigPath,
                Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    public TargetSpec(String id, String packageName, String displayName, String marketUri,
                      String domainRule, int support, String legacyConfigPath,
                      List<String> identityMarkers, List<String> integrityMarkers) {
        this.id = requireToken(id, "target id");
        this.packageName = requirePackage(packageName);
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.marketUri = Objects.requireNonNull(marketUri, "marketUri");
        this.domainRule = domainRule == null ? "" : domainRule;
        if (support != SUPPORT_DIAGNOSTIC && support != SUPPORT_ENVIRONMENT
                && support != SUPPORT_FULL) {
            throw new IllegalArgumentException("unknown support level: " + support);
        }
        this.support = support;
        this.legacyConfigPath = legacyConfigPath == null ? "" : legacyConfigPath;
        this.identityMarkers = markers(identityMarkers);
        this.integrityMarkers = markers(integrityMarkers);
    }

    private static List<String> markers(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }

    public String id() {
        return id;
    }

    public String packageName() {
        return packageName;
    }

    public String displayName() {
        return displayName;
    }

    public String marketUri() {
        return marketUri;
    }

    /** Domain-rule suffix grep used by the network card; empty when the target has no map. */
    public String domainRule() {
        return domainRule;
    }

    public int support() {
        return support;
    }

    public boolean hooksEnabled() {
        return support >= SUPPORT_ENVIRONMENT;
    }

    /** Region writes and persona publication are allowed. */
    public boolean environmentWrites() {
        return support >= SUPPORT_ENVIRONMENT;
    }

    /** Per-app identity/data edits are allowed. */
    public boolean localIdentityWrites() {
        return support >= SUPPORT_FULL;
    }

    public boolean diagnosticOnly() {
        return support == SUPPORT_DIAGNOSTIC;
    }

    /**
     * Substrings searched in local file names only. A hit is an inventory signal, not proof that
     * a value is used or that it is the identity the service evaluates.
     */
    public List<String> identityMarkers() {
        return identityMarkers;
    }

    /** Substrings naming local integrity/captcha/device-key stores; values are never read. */
    public List<String> integrityMarkers() {
        return integrityMarkers;
    }

    /** Canonical per-target payload path read by the Vector module. */
    public String configPath() {
        return "/data/local/tmp/claudewilson/" + id + ".json";
    }

    /** Pre-multi-target payload path, read only when the canonical file is absent. */
    public String legacyConfigPath() {
        return legacyConfigPath;
    }

    @Override public String toString() {
        return id;
    }

    private static String requireToken(String value, String name) {
        if (value == null || !value.matches("[a-z0-9-]{1,24}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    private static String requirePackage(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("invalid package name: " + value);
        }
        return value;
    }
}
