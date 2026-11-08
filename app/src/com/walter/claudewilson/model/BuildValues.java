package com.walter.claudewilson.model;

/**
 * Immutable view of the build fields the framework exposes. Plain data: no Android types, so
 * the rules that compare these values run on a desktop JVM in tests.
 */
public final class BuildValues {
    public static final BuildValues EMPTY = new Builder().build();

    private final String manufacturer;
    private final String brand;
    private final String model;
    private final String device;
    private final String product;
    private final String type;
    private final String buildId;
    private final String incremental;
    private final String display;
    private final String fingerprint;
    private final String tags;
    private final String firstApiLevel;
    private final long time;

    private BuildValues(Builder b) {
        this.manufacturer = b.manufacturer;
        this.brand = b.brand;
        this.model = b.model;
        this.device = b.device;
        this.product = b.product;
        this.type = b.type;
        this.buildId = b.buildId;
        this.incremental = b.incremental;
        this.display = b.display;
        this.fingerprint = b.fingerprint;
        this.tags = b.tags;
        this.firstApiLevel = b.firstApiLevel;
        this.time = b.time;
    }

    public String manufacturer() {
        return manufacturer;
    }

    public String brand() {
        return brand;
    }

    public String model() {
        return model;
    }

    public String device() {
        return device;
    }

    public String product() {
        return product;
    }

    public String type() {
        return type;
    }

    public String buildId() {
        return buildId;
    }

    public String incremental() {
        return incremental;
    }

    public String display() {
        return display;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String tags() {
        return tags;
    }

    public String firstApiLevel() {
        return firstApiLevel;
    }

    public long time() {
        return time;
    }

    /** True when nothing was collected, which rules must treat as unknown rather than clean. */
    public boolean isEmpty() {
        return fingerprint.isEmpty() && brand.isEmpty() && model.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with these values, for "same device, one field changed" fixtures. */
    public Builder toBuilder() {
        return new Builder()
                .manufacturer(manufacturer)
                .brand(brand)
                .model(model)
                .device(device)
                .product(product)
                .type(type)
                .buildId(buildId)
                .incremental(incremental)
                .display(display)
                .fingerprint(fingerprint)
                .tags(tags)
                .firstApiLevel(firstApiLevel)
                .time(time);
    }

    public static final class Builder {
        private String manufacturer = "";
        private String brand = "";
        private String model = "";
        private String device = "";
        private String product = "";
        private String type = "";
        private String buildId = "";
        private String incremental = "";
        private String display = "";
        private String fingerprint = "";
        private String tags = "";
        private String firstApiLevel = "";
        private long time;

        public Builder manufacturer(String v) {
            manufacturer = nz(v);
            return this;
        }

        public Builder brand(String v) {
            brand = nz(v);
            return this;
        }

        public Builder model(String v) {
            model = nz(v);
            return this;
        }

        public Builder device(String v) {
            device = nz(v);
            return this;
        }

        public Builder product(String v) {
            product = nz(v);
            return this;
        }

        public Builder type(String v) {
            type = nz(v);
            return this;
        }

        public Builder buildId(String v) {
            buildId = nz(v);
            return this;
        }

        public Builder incremental(String v) {
            incremental = nz(v);
            return this;
        }

        public Builder display(String v) {
            display = nz(v);
            return this;
        }

        public Builder fingerprint(String v) {
            fingerprint = nz(v);
            return this;
        }

        public Builder tags(String v) {
            tags = nz(v);
            return this;
        }

        public Builder firstApiLevel(String v) {
            firstApiLevel = nz(v);
            return this;
        }

        public Builder time(long v) {
            time = v;
            return this;
        }

        public BuildValues build() {
            return new BuildValues(this);
        }

        private static String nz(String v) {
            return v == null ? "" : v;
        }
    }
}
