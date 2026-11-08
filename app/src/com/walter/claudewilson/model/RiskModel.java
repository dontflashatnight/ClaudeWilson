package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The evaluated check-item model for one target.
 *
 * <p>Evaluation is intentionally two-step: item-local facts first, then cross-item constraints.
 * A constraint that cannot be evaluated marks its participants {@link CheckItem.Verdict#UNKNOWN};
 * it never leaves them looking consistent. Boundary items remain visible as boundary unless a
 * constraint proves a contradiction, in which case they become a conflict that cannot be fixed
 * locally.
 */
public final class RiskModel {
    public static final RiskModel EMPTY = new RiskModel(Collections.<CheckItem>emptyList(),
            Collections.<CheckConstraint>emptyList());

    private final List<CheckItem> items;
    private final List<CheckConstraint> constraints;

    private RiskModel(List<CheckItem> items, List<CheckConstraint> constraints) {
        this.items = Collections.unmodifiableList(new ArrayList<CheckItem>(items));
        this.constraints = Collections.unmodifiableList(
                new ArrayList<CheckConstraint>(constraints));
    }

    public static RiskModel evaluate(List<CheckItem> specs, List<CheckConstraint> definitions) {
        Map<String, CheckItem> byId = new LinkedHashMap<String, CheckItem>();
        for (CheckItem spec : specs) {
            byId.put(spec.id(), spec);
        }
        List<CheckConstraint> evaluated = new ArrayList<CheckConstraint>(definitions.size());
        Map<String, Boolean> conflict = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> unknown = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> boundary = new LinkedHashMap<String, Boolean>();
        for (CheckConstraint definition : definitions) {
            CheckConstraint result = definition.evaluate(byId);
            evaluated.add(result);
            mark(conflict, result.leftId(), result.verdict() == CheckItem.Verdict.CONFLICT);
            mark(conflict, result.rightId(), result.verdict() == CheckItem.Verdict.CONFLICT);
            mark(unknown, result.leftId(), result.verdict() == CheckItem.Verdict.UNKNOWN);
            mark(unknown, result.rightId(), result.verdict() == CheckItem.Verdict.UNKNOWN);
            mark(boundary, result.leftId(), result.verdict() == CheckItem.Verdict.BOUNDARY);
            mark(boundary, result.rightId(), result.verdict() == CheckItem.Verdict.BOUNDARY);
        }
        List<CheckItem> resolved = new ArrayList<CheckItem>(specs.size());
        for (CheckItem spec : specs) {
            CheckItem.Verdict base = baseVerdict(spec);
            if (Boolean.TRUE.equals(conflict.get(spec.id()))) {
                base = CheckItem.Verdict.CONFLICT;
            } else if (base != CheckItem.Verdict.UNKNOWN
                    && Boolean.TRUE.equals(unknown.get(spec.id()))) {
                base = CheckItem.Verdict.UNKNOWN;
            } else if (base != CheckItem.Verdict.CONFLICT
                    && Boolean.TRUE.equals(boundary.get(spec.id()))) {
                base = CheckItem.Verdict.BOUNDARY;
            }
            resolved.add(spec.withVerdict(base));
        }
        return new RiskModel(resolved, evaluated);
    }

    private static void mark(Map<String, Boolean> map, String id, boolean value) {
        if (id == null || id.isEmpty() || !value) {
            return;
        }
        map.put(id, Boolean.TRUE);
    }

    private static CheckItem.Verdict baseVerdict(CheckItem item) {
        if (item.isStale() || !item.isRead()) {
            return CheckItem.Verdict.UNKNOWN;
        }
        String expected = item.expected();
        if (expected != null) {
            boolean pass = item.value().trim().equalsIgnoreCase(expected.trim());
            return pass ? (item.boundary() ? CheckItem.Verdict.BOUNDARY
                    : CheckItem.Verdict.CONSISTENT) : CheckItem.Verdict.CONFLICT;
        }
        return item.boundary() ? CheckItem.Verdict.BOUNDARY : CheckItem.Verdict.CONSISTENT;
    }

    public List<CheckItem> items() {
        return items;
    }

    public List<CheckConstraint> constraints() {
        return constraints;
    }

    public CheckItem item(String id) {
        for (CheckItem item : items) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Replace one observation and re-evaluate every relation. Used by the asynchronous Play
     * country callback: the item must become readable without waiting for a full rescan, and it
     * must not leave the other items' verdicts stale.
     */
    public RiskModel withValue(String id, String value) {
        List<CheckItem> next = new ArrayList<CheckItem>(items.size());
        boolean found = false;
        for (CheckItem item : items) {
            if (item.id().equals(id)) {
                found = true;
                next.add(value == null ? item.unreadable() : item.withValue(value));
            } else {
                next.add(item);
            }
        }
        return found ? evaluate(next, constraints) : this;
    }

    /** Channels in definition order, so the screen does not reshuffle between scans. */
    public List<String> channels() {
        Set<String> channels = new LinkedHashSet<String>();
        for (CheckItem item : items) {
            channels.add(item.channel());
        }
        return Collections.unmodifiableList(new ArrayList<String>(channels));
    }

    public List<CheckItem> items(String channel) {
        List<CheckItem> out = new ArrayList<CheckItem>();
        for (CheckItem item : items) {
            if (item.channel().equals(channel)) {
                out.add(item);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public int count(CheckItem.Verdict verdict) {
        int total = 0;
        for (CheckItem item : items) {
            if (item.verdict() == verdict) {
                total++;
            }
        }
        return total;
    }

    /** Any conflict outranks an unknown; boundary items alone do not block the model. */
    public CheckItem.Verdict verdict() {
        if (count(CheckItem.Verdict.CONFLICT) > 0) {
            return CheckItem.Verdict.CONFLICT;
        }
        if (count(CheckItem.Verdict.UNKNOWN) > 0) {
            return CheckItem.Verdict.UNKNOWN;
        }
        return CheckItem.Verdict.CONSISTENT;
    }

    public List<CheckConstraint> failuresFor(String itemId) {
        List<CheckConstraint> out = new ArrayList<CheckConstraint>();
        for (CheckConstraint constraint : constraints) {
            if (constraint.verdict() == CheckItem.Verdict.CONFLICT
                    && (constraint.leftId().equals(itemId)
                    || constraint.rightId().equals(itemId))) {
                out.add(constraint);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Relations that proved a mismatch but are not locally fixable. They stay boundary, yet the
     * two observed values should be shown together instead of leaving one side hidden.
     */
    public List<CheckConstraint> mismatchesFor(String itemId) {
        List<CheckConstraint> out = new ArrayList<CheckConstraint>();
        for (CheckConstraint constraint : constraints) {
            if ((constraint.verdict() == CheckItem.Verdict.CONFLICT
                    || constraint.verdict() == CheckItem.Verdict.BOUNDARY)
                    && (constraint.leftId().equals(itemId)
                    || constraint.rightId().equals(itemId))) {
                out.add(constraint);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<CheckConstraint> unknownsFor(String itemId) {
        List<CheckConstraint> out = new ArrayList<CheckConstraint>();
        for (CheckConstraint constraint : constraints) {
            if (constraint.verdict() == CheckItem.Verdict.UNKNOWN
                    && (constraint.leftId().equals(itemId)
                    || constraint.rightId().equals(itemId))) {
                out.add(constraint);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public boolean hasBoundaries() {
        return count(CheckItem.Verdict.BOUNDARY) > 0;
    }

    /** One ASCII line, used by tests and the report export. */
    public String summary() {
        return "consistent=" + count(CheckItem.Verdict.CONSISTENT)
                + " conflict=" + count(CheckItem.Verdict.CONFLICT)
                + " boundary=" + count(CheckItem.Verdict.BOUNDARY)
                + " unknown=" + count(CheckItem.Verdict.UNKNOWN)
                + " verdict=" + verdict();
    }

    @Override public String toString() {
        return summary();
    }
}
