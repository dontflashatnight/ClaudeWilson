package com.walter.claudewilson.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target permission facts, read from the target's manifest and PackageManager in M1 and from
 * {@code appops} in M2.
 *
 * <p>Declared and granted are separate. "Not requested" is not "denied", and a permission that is
 * declared but not granted is a fact about the target, not a failure of this tool.
 */
public final class PermissionFacts {
    public enum State {
        GRANTED, DENIED, NOT_REQUESTED, APPROXIMATE, UNKNOWN
    }

    public static final PermissionFacts EMPTY = new PermissionFacts(
            Collections.<String, State>emptyMap(), Collections.<String, String>emptyMap(),
            false, "");

    private final Map<String, State> states;
    private final Map<String, String> appOps;
    private final boolean readable;
    private final String source;

    private PermissionFacts(Map<String, State> states, Map<String, String> appOps,
            boolean readable, String source) {
        this.states = Collections.unmodifiableMap(states);
        this.appOps = Collections.unmodifiableMap(appOps);
        this.readable = readable;
        this.source = source == null ? "" : source;
    }

    public boolean readable() {
        return readable;
    }

    public String source() {
        return source;
    }

    public State state(String key) {
        State state = states.get(key);
        return state == null ? State.UNKNOWN : state;
    }

    /** AppOps mode word for a location permission, or empty when no privileged channel read it. */
    public String appOp(String key) {
        String value = appOps.get(key);
        return value == null ? "" : value;
    }

    public static final class Builder {
        private final Map<String, State> states = new LinkedHashMap<>();
        private final Map<String, String> appOps = new LinkedHashMap<>();
        private boolean readable;
        private String source = "package-manager";

        public Builder put(String key, boolean declared, boolean granted) {
            State state;
            if (!declared) {
                state = State.NOT_REQUESTED;
            } else if (granted) {
                state = State.GRANTED;
            } else {
                state = State.DENIED;
            }
            states.put(key, state);
            return this;
        }

        public Builder approximate(String key) {
            states.put(key, State.APPROXIMATE);
            return this;
        }

        public Builder appOp(String key, String mode) {
            appOps.put(key, mode == null ? "" : mode);
            return this;
        }

        public Builder readable(boolean value) {
            readable = value;
            return this;
        }

        public Builder source(String value) {
            source = value == null ? "" : value;
            return this;
        }

        public PermissionFacts build() {
            return new PermissionFacts(states, appOps, readable, source);
        }
    }
}
