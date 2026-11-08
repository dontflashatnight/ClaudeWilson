package com.walter.claudewilson.model;

import java.util.Objects;

/**
 * One item the target's risk surface actually reads.
 *
 * <p>The item is a definition plus one observation: {@code source} names where the target reads
 * it, {@code value} is what was observed, and {@code writable} says whether this tool has a real
 * write path. A spec (unread, no value) becomes an item through {@link #withValue(String)}; a
 * failed read keeps its blind-spot reason instead of becoming an empty string.
 *
 * <p>Boundary items stay visible even when they cannot be changed. They are the part of the
 * model that must be shown as a residual rather than silently dropped.
 */
public final class CheckItem {
    public enum Writable {
        /** A local write path exists. */
        YES,
        /** No write path; the item is read-only observation. */
        NO,
        /** Server-bound or cryptographically bound; can be observed but not produced locally. */
        BOUNDARY
    }

    public enum Verdict {
        CONSISTENT, CONFLICT, BOUNDARY, UNKNOWN
    }

    /**
     * Why a value is not available. This is not a verdict: it explains whether the gap is the
     * tool (not covered), the target (no record yet), the file (unreadable), or the architecture
     * (server-only). UI must not turn any of these into an action.
     */
    public enum Coverage {
        OBSERVED,
        UNREADABLE,
        NO_RECORD,
        NOT_COVERED,
        SERVER_ONLY,
        STALE
    }

    private final String id;
    private final String channel;
    private final String source;
    private final String expected;
    private final Writable writable;
    private final String writePath;
    private final boolean boundary;
    private final boolean sensitive;
    private final boolean read;
    private final String value;
    private final boolean stale;
    private final Coverage blind;
    private final Coverage coverage;
    private final Verdict verdict;
    private final String note;

    private CheckItem(String id, String channel, String source, String expected, Writable writable,
                      String writePath, boolean boundary, boolean sensitive, boolean read,
                      String value, boolean stale, Coverage blind, Coverage coverage,
                      Verdict verdict, String note) {
        this.id = Objects.requireNonNull(id, "id");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.source = source == null ? "" : source;
        this.expected = expected;
        this.writable = Objects.requireNonNull(writable, "writable");
        this.writePath = writePath == null ? "" : writePath;
        this.boundary = boundary;
        this.sensitive = sensitive;
        this.read = read;
        this.value = value == null ? "" : value;
        this.stale = stale;
        this.blind = Objects.requireNonNull(blind, "blind");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.note = note == null ? "" : note;
    }

    public static CheckItem spec(String id, String channel, String source, Writable writable,
                                 String writePath, boolean boundary, boolean sensitive,
                                 String expected) {
        return spec(id, channel, source, writable, writePath, boundary, sensitive, expected,
                Coverage.UNREADABLE);
    }

    public static CheckItem spec(String id, String channel, String source, Writable writable,
                                 String writePath, boolean boundary, boolean sensitive,
                                 String expected, Coverage blind) {
        Coverage start = blind == Coverage.OBSERVED ? Coverage.UNREADABLE : blind;
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, false, "", false, start, start, Verdict.UNKNOWN, "");
    }

    /** A successful read. An empty value is a valid observation (for example "not present"). */
    public CheckItem withValue(String observed) {
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, true, observed, stale, blind, Coverage.OBSERVED, verdict, note);
    }

    public CheckItem unreadable() {
        Coverage next = blind == Coverage.OBSERVED ? Coverage.UNREADABLE : blind;
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, false, "", stale, blind, next, Verdict.UNKNOWN, note);
    }

    /** The target is not running, so the last self-check is not the current target view. */
    public CheckItem stale() {
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, read, value, true, blind, Coverage.STALE, verdict, note);
    }

    public CheckItem withNote(String text) {
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, read, value, stale, blind, coverage, verdict, text);
    }

    public CheckItem withVerdict(Verdict next) {
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, read, value, stale, blind, coverage, next, note);
    }

    public CheckItem withCoverage(Coverage next) {
        return new CheckItem(id, channel, source, expected, writable, writePath, boundary,
                sensitive, read, value, stale, blind, next, verdict, note);
    }

    public String id() {
        return id;
    }

    public String channel() {
        return channel;
    }

    /** Machine-readable read path, for example {@code files/INSTALLATION}. */
    public String source() {
        return source;
    }

    /** Optional exact expected value. Null means "no fixed expectation". */
    public String expected() {
        return expected;
    }

    public Writable writable() {
        return writable;
    }

    /** Machine-readable write path code; empty when none. */
    public String writePath() {
        return writePath;
    }

    public boolean boundary() {
        return boundary;
    }

    /** True when the value is a token or another secret that must not be rendered in full. */
    public boolean sensitive() {
        return sensitive;
    }

    public boolean isRead() {
        return read;
    }

    public String value() {
        return value;
    }

    public boolean isStale() {
        return stale;
    }

    /** Current blind-spot reason; {@link Coverage#OBSERVED} when a value exists. */
    public Coverage coverage() {
        return coverage;
    }

    /** The reason a future failed read returns to, independent of the current observation. */
    public Coverage blindCoverage() {
        return blind;
    }

    public Verdict verdict() {
        return verdict;
    }

    /** Machine-readable reason code, never user-facing prose. */
    public String note() {
        return note;
    }

    @Override public String toString() {
        return id + "=" + (read ? value : "<unreadable>") + " " + verdict
                + (coverage == Coverage.OBSERVED ? "" : " " + coverage);
    }
}
