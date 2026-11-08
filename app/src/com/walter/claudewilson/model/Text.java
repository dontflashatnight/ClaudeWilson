package com.walter.claudewilson.model;

/**
 * The result of reading one value: either the read succeeded (possibly with an empty value,
 * which is a legitimate answer such as "property not set") or it did not.
 *
 * <p>This is the difference the old code lost: {@code catch (Throwable) { return null; }}
 * made "not set" and "denied" indistinguishable, and every caller then defaulted to a pass.
 */
public final class Text {
    private static final Text UNREADABLE = new Text(false, "");

    private final boolean readable;
    private final String value;

    private Text(boolean readable, String value) {
        this.readable = readable;
        this.value = value;
    }

    /** A successful read; {@code raw} may be null, which becomes an empty value. */
    public static Text of(String raw) {
        return new Text(true, raw == null ? "" : raw.trim());
    }

    public static Text unreadable() {
        return UNREADABLE;
    }

    public boolean isReadable() {
        return readable;
    }

    public String value() {
        return value;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public String toString() {
        return readable ? value : "<unreadable>";
    }
}
