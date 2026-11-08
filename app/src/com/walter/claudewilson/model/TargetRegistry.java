package com.walter.claudewilson.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The only list of supported applications.
 *
 * <p>Package ids are data. Adding a target is one entry here plus an adapter when it needs
 * hooks; no screen, shell command or probe may compare against a package literal.
 */
public final class TargetRegistry {
    public static final TargetSpec CLAUDE = new TargetSpec(
            "claude",
            "com.anthropic.claude",
            "Claude",
            "market://details?id=com.anthropic.claude",
            "(anthropic|claude)",
            TargetSpec.SUPPORT_FULL,
            "/data/local/tmp/persona.json");

    /**
     * The full local model is now enabled: the analysis' L1 identity reset is a local-data reset
     * (pm clear), and the UI asks for confirmation before it. Server-side account/integrity
     * remain explicit boundaries.
     */
    public static final TargetSpec CHATGPT = new TargetSpec(
            "chatgpt",
            "com.openai.chatgpt",
            "ChatGPT",
            "market://details?id=com.openai.chatgpt",
            "(openai|chatgpt|oaistatic|oaiusercontent)",
            TargetSpec.SUPPORT_FULL,
            null,
            Arrays.asList("segment", "sentry", "datadog", "firebase", "android_id",
                    "device_id", "uuid", "installation", "account_id"),
            Arrays.asList("integrity", "attestation", "captcha", "codex", "device_key",
                    "playintegrity", "hcaptcha"));

    private static final List<TargetSpec> ALL =
            Collections.unmodifiableList(Arrays.asList(CLAUDE, CHATGPT));

    private TargetRegistry() {
    }

    public static List<TargetSpec> all() {
        return ALL;
    }

    /** The spec for a package, or null when the package is outside the registry. */
    public static TargetSpec byPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (TargetSpec spec : ALL) {
            if (spec.packageName().equals(packageName)) {
                return spec;
            }
        }
        return null;
    }

    /** Stored selections survive releases that drop a target; an unknown id falls back. */
    public static TargetSpec byId(String id) {
        if (id != null) {
            for (TargetSpec spec : ALL) {
                if (spec.id().equals(id)) {
                    return spec;
                }
            }
        }
        return CLAUDE;
    }
}
