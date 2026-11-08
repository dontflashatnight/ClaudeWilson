package com.walter.claudewilson.model;

/**
 * Grouping of probes for display. Ids are stable, lower-case and dotted; the renderer owns
 * the display name. Keeping ids here instead of display strings means the grouping survives
 * a language change and can be asserted in tests.
 */
public enum Layer {
    ENV_EVIDENCE("env.evidence"),
    IDENTITY("identity.coherence"),
    PLATFORM("platform.integrity"),
    NETWORK("network.tls"),
    TARGET("target.state"),
    ACCOUNT("account");

    private final String id;

    Layer(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
