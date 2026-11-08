package com.walter.claudewilson.model;

/**
 * Outcome of one probe.
 *
 * <p>{@link #UNKNOWN} is a first-class state: the signal could not be observed (permission
 * denied, file absent, binary missing, catalog not loaded). It must never be folded into
 * {@link #PASS}. A pipeline that cannot tell "clean" from "could not look" reports a green
 * check for a device it never inspected, which is the failure mode this layer removes.
 *
 * <p>{@link #INFO} is neutral: it carries an observation that is not a grading decision.
 */
public enum Status {
    PASS, INFO, WARN, FAIL, UNKNOWN;

    /**
     * Aggregate two outcomes. Order of precedence: FAIL &gt; WARN &gt; UNKNOWN &gt; PASS,
     * with INFO being neutral and only surfacing when nothing else was observed.
     */
    public static Status worst(Status a, Status b) {
        if (a == FAIL || b == FAIL) return FAIL;
        if (a == WARN || b == WARN) return WARN;
        if (a == UNKNOWN || b == UNKNOWN) return UNKNOWN;
        if (a == INFO || b == INFO) return INFO;
        return PASS;
    }
}
