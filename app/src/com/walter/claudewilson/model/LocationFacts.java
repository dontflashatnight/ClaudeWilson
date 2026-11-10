package com.walter.claudewilson.model;

/**
 * Device and target location state. Coordinates are deliberately absent: the model stores the
 * mode, provider availability, mock status and last-request age, never a latitude/longitude.
 */
public final class LocationFacts {
    public enum Mode {
        OFF, SENSORS, BATTERY, HIGH, UNKNOWN
    }

    public static final LocationFacts EMPTY = new LocationFacts(
            Mode.UNKNOWN, "", false, false, false, false, "", -1L, false, "");

    private final Mode mode;
    private final String providers;
    private final boolean gps;
    private final boolean network;
    private final boolean mock;
    private final boolean mockKnown;
    private final String lastRequest;
    private final long lastRequestAgeMs;
    private final boolean readable;
    private final String source;

    private LocationFacts(Mode mode, String providers, boolean gps, boolean network, boolean mock,
            boolean mockKnown, String lastRequest, long lastRequestAgeMs, boolean readable,
            String source) {
        this.mode = mode == null ? Mode.UNKNOWN : mode;
        this.providers = providers == null ? "" : providers;
        this.gps = gps;
        this.network = network;
        this.mock = mock;
        this.mockKnown = mockKnown;
        this.lastRequest = lastRequest == null ? "" : lastRequest;
        this.lastRequestAgeMs = lastRequestAgeMs;
        this.readable = readable;
        this.source = source == null ? "" : source;
    }

    public Mode mode() {
        return mode;
    }

    public String providers() {
        return providers;
    }

    public boolean gps() {
        return gps;
    }

    public boolean network() {
        return network;
    }

    public boolean mock() {
        return mock;
    }

    public boolean mockKnown() {
        return mockKnown;
    }

    public String lastRequest() {
        return lastRequest;
    }

    public long lastRequestAgeMs() {
        return lastRequestAgeMs;
    }

    public boolean readable() {
        return readable;
    }

    public String source() {
        return source;
    }

    public String modeLabel() {
        switch (mode) {
            case OFF:
                return "off";
            case SENSORS:
                return "sensors-only";
            case BATTERY:
                return "battery-saving";
            case HIGH:
                return "high-accuracy";
            default:
                return "unknown";
        }
    }

    public static final class Builder {
        private Mode mode = Mode.UNKNOWN;
        private String providers = "";
        private boolean gps;
        private boolean network;
        private boolean mock;
        private boolean mockKnown;
        private String lastRequest = "";
        private long lastRequestAgeMs = -1L;
        private boolean readable;
        private String source = "settings";

        public Builder mode(Mode value) {
            mode = value == null ? Mode.UNKNOWN : value;
            return this;
        }

        public Builder providers(String value) {
            providers = value == null ? "" : value;
            return this;
        }

        public Builder gps(boolean value) {
            gps = value;
            return this;
        }

        public Builder network(boolean value) {
            network = value;
            return this;
        }

        public Builder mock(boolean value) {
            mock = value;
            mockKnown = true;
            return this;
        }

        public Builder lastRequest(String value, long ageMs) {
            lastRequest = value == null ? "" : value;
            lastRequestAgeMs = ageMs;
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

        public LocationFacts build() {
            return new LocationFacts(mode, providers, gps, network, mock, mockKnown, lastRequest,
                    lastRequestAgeMs, readable, source);
        }
    }
}
