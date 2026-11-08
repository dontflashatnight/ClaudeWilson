package com.walter.claudewilson.model;

/** One row of the mount table, as plain data. */
public final class Mount {
    private final String source;
    private final String mountPoint;
    private final String fsType;
    private final String options;

    public Mount(String source, String mountPoint, String fsType, String options) {
        this.source = source == null ? "" : source;
        this.mountPoint = mountPoint == null ? "" : mountPoint;
        this.fsType = fsType == null ? "" : fsType;
        this.options = options == null ? "" : options;
    }

    public String source() {
        return source;
    }

    public String mountPoint() {
        return mountPoint;
    }

    public String fsType() {
        return fsType;
    }

    public String options() {
        return options;
    }

    public boolean isReadWrite() {
        return options.startsWith("rw");
    }

    @Override
    public String toString() {
        return mountPoint + " on " + fsType + " from " + source + " (" + options + ")";
    }
}
