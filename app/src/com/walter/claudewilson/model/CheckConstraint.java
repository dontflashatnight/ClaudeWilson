package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One relation two observed check items must satisfy for the model to be consistent.
 *
 * <p>The relation is deliberately small and value-based. It never invents a value: a missing or
 * stale side produces {@link CheckItem.Verdict#UNKNOWN}, which is not a pass.
 */
public final class CheckConstraint {
    public enum Relation {
        /** Trimmed, case-insensitive equality. */
        EQUALS,
        /** The left value occurs inside the right value (for example a UUID inside {@code ua-<uuid>}). */
        CONTAINS,
        /** Both sides are readable and differ. */
        NOT_EQUALS,
        /** Both locales name the same language (zh-Hans-CN and zh_CN share zh). */
        SAME_LANGUAGE,
        /** Both values end in the same two-letter country/region token. */
        SAME_COUNTRY,
        /**
         * The right value is {@code manufacturer|device|product|hardware|model}; the left is a
         * build fingerprint whose first three segments are {@code brand/product/device}.
         */
        BUILD_FINGERPRINT,
        /** The left item was read and is non-empty. */
        PRESENT
    }

    private final String id;
    private final String leftId;
    private final String rightId;
    private final Relation relation;
    private final CheckItem.Verdict verdict;
    private final String detail;

    private CheckConstraint(String id, String leftId, String rightId, Relation relation,
                            CheckItem.Verdict verdict, String detail) {
        this.id = Objects.requireNonNull(id, "id");
        this.leftId = Objects.requireNonNull(leftId, "leftId");
        this.rightId = rightId == null ? "" : rightId;
        this.relation = Objects.requireNonNull(relation, "relation");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.detail = detail == null ? "" : detail;
    }

    public static CheckConstraint of(String id, String leftId, Relation relation, String rightId) {
        return new CheckConstraint(id, leftId, rightId, relation,
                CheckItem.Verdict.UNKNOWN, "");
    }

    public CheckConstraint evaluate(Map<String, CheckItem> items) {
        CheckItem left = items.get(leftId);
        if (left == null || !left.isRead()) {
            return done(CheckItem.Verdict.UNKNOWN, "left unread: " + leftId);
        }
        if (left.isStale()) {
            return done(CheckItem.Verdict.UNKNOWN, "left stale: " + leftId);
        }
        if (relation == Relation.PRESENT) {
            if (!left.value().isEmpty()) {
                return done(CheckItem.Verdict.CONSISTENT, "present: " + leftId);
            }
            return done(actionable(left, left) ? CheckItem.Verdict.CONFLICT
                    : CheckItem.Verdict.BOUNDARY, "empty: " + leftId);
        }
        CheckItem right = items.get(rightId);
        if (right == null || !right.isRead()) {
            return done(CheckItem.Verdict.UNKNOWN, "right unread: " + rightId);
        }
        if (right.isStale()) {
            return done(CheckItem.Verdict.UNKNOWN, "right stale: " + rightId);
        }
        String a = left.value();
        String b = right.value();
        if (a.isEmpty() || b.isEmpty()) {
            return done(CheckItem.Verdict.UNKNOWN, "empty side: " + leftId + "/" + rightId);
        }
        switch (relation) {
            case EQUALS:
                return compare(normalize(a).equals(normalize(b)), left, right);
            case CONTAINS:
                return compare(normalize(b).contains(normalize(a)), left, right);
            case NOT_EQUALS:
                return compare(!normalize(a).equals(normalize(b)), left, right);
            case SAME_LANGUAGE: {
                String la = languageOf(a);
                String lb = languageOf(b);
                if (la.isEmpty() || lb.isEmpty()) {
                    return done(CheckItem.Verdict.UNKNOWN,
                            "no language: " + leftId + "/" + rightId);
                }
                return compare(la.equals(lb), left, right);
            }
            case SAME_COUNTRY: {
                String ca = countryOf(a);
                String cb = countryOf(b);
                if (ca.isEmpty() || cb.isEmpty()) {
                    return done(CheckItem.Verdict.UNKNOWN,
                            "no country: " + leftId + "/" + rightId);
                }
                return compare(ca.equals(cb), left, right);
            }
            case BUILD_FINGERPRINT: {
                String[] fp = normalize(a).split("[/:]");
                String[] parts = normalize(b).split("\\|");
                if (fp.length < 3 || parts.length < 3) {
                    return done(CheckItem.Verdict.UNKNOWN,
                            "no build parts: " + leftId + "/" + rightId);
                }
                boolean pass = parts[0].equals(fp[0])
                        && parts[1].equals(fp[2])
                        && parts[2].equals(fp[1]);
                return compare(pass, left, right);
            }
            default:
                return done(CheckItem.Verdict.UNKNOWN, "unhandled relation");
        }
    }

    private CheckConstraint done(CheckItem.Verdict next, String text) {
        return new CheckConstraint(id, leftId, rightId, relation, next, text);
    }

    private CheckConstraint compare(boolean pass, CheckItem left, CheckItem right) {
        String detail = left.id() + "=" + left.value() + " vs "
                + right.id() + "=" + right.value();
        if (pass) {
            return done(CheckItem.Verdict.CONSISTENT, detail);
        }
        if (actionable(left, right)) {
            return done(CheckItem.Verdict.CONFLICT, detail);
        }
        return done(CheckItem.Verdict.BOUNDARY, detail + " · not locally fixable");
    }

    /**
     * A mismatch is actionable only when both sides have a real local write path. One
     * server-bound side makes the row a boundary, not a red conflict the user cannot fix.
     */
    private static boolean actionable(CheckItem left, CheckItem right) {
        return left.writable() == CheckItem.Writable.YES
                && right.writable() == CheckItem.Writable.YES
                && !left.boundary() && !right.boundary();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** First language subtag; zh-Hans-CN, zh_CN_#Hans and zh-CN all reduce to zh. */
    static String languageOf(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        int hash = text.indexOf('#');
        if (hash >= 0) {
            text = text.substring(0, hash);
        }
        for (String part : text.split("[^a-z]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.startsWith("zh")) {
                return "zh";
            }
            if (part.startsWith("en")) {
                return "en";
            }
            return part.length() > 2 ? part.substring(0, 2) : part;
        }
        return "";
    }

    /**
     * Last two-letter token in a locale/country value: zh-Hans-CN =&gt; CN, en-GB =&gt; GB,
     * {@code cn,cn} =&gt; CN. Numeric MCC values deliberately produce no country here; those are
     * compared directly with {@link Relation#EQUALS}.
     */
    static String countryOf(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        int hash = text.indexOf('#');
        if (hash >= 0) {
            text = text.substring(0, hash);
        }
        String last = "";
        for (String part : text.split("[^a-z]+")) {
            if (part.length() == 2) {
                last = part.toUpperCase(Locale.ROOT);
            }
        }
        return last;
    }

    public String id() {
        return id;
    }

    public String leftId() {
        return leftId;
    }

    public String rightId() {
        return rightId;
    }

    public Relation relation() {
        return relation;
    }

    public CheckItem.Verdict verdict() {
        return verdict;
    }

    public String detail() {
        return detail;
    }

    public boolean isFailure() {
        return verdict == CheckItem.Verdict.CONFLICT;
    }

    @Override public String toString() {
        return id + ": " + leftId + " " + relation + " " + rightId + " -> " + verdict;
    }
}
