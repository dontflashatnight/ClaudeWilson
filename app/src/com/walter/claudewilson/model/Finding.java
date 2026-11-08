package com.walter.claudewilson.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one probe: what was observed, how it grades, and why.
 *
 * <p>{@link #observed()} holds the raw value and {@link #detail()} holds machine-readable
 * parameters. Neither carries prose -- the renderer owns every user-facing string, which is
 * what lets the same finding be shown in several languages and asserted in a test.
 */
public final class Finding {
    private final String probeId;
    private final String layerId;
    private final String field;
    private final Status status;
    private final Reason reason;
    private final String observed;
    private final Map<String, String> detail;

    private Finding(String probeId, String layerId, String field, Status status, Reason reason,
                    String observed, Map<String, String> detail) {
        this.probeId = Objects.requireNonNull(probeId, "probeId");
        this.layerId = Objects.requireNonNull(layerId, "layerId");
        this.field = field == null ? "" : field;
        this.status = Objects.requireNonNull(status, "status");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.observed = observed == null ? "" : observed;
        this.detail = Collections.unmodifiableMap(new LinkedHashMap<String, String>(detail));
    }

    public static Finding of(String probeId, Layer layer, String field, Status status,
                             Reason reason, String observed) {
        return new Finding(probeId, layer.id(), field, status, reason, observed,
                Collections.<String, String>emptyMap());
    }

    /** Copy with one extra machine-readable parameter. Immutable, so this is total. */
    public Finding with(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<String, String>(detail);
        copy.put(key, value == null ? "" : value);
        return new Finding(probeId, layerId, field, status, reason, observed, copy);
    }

    public String probeId() {
        return probeId;
    }

    public String layerId() {
        return layerId;
    }

    public String field() {
        return field;
    }

    public Status status() {
        return status;
    }

    public Reason reason() {
        return reason;
    }

    public String observed() {
        return observed;
    }

    public Map<String, String> detail() {
        return detail;
    }

    @Override
    public String toString() {
        return probeId + " " + status + " (" + reason + ")" + (observed.isEmpty() ? ""
                : " observed=" + observed);
    }
}
