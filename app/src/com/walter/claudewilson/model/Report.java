package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All findings of one scan.
 *
 * <p>Two invariants live here rather than in the callers, because a rule that is not enforced
 * gets forgotten: duplicate probe ids are rejected, and {@link #verdict()} never collapses an
 * {@link Status#UNKNOWN} into {@link Status#PASS}. {@link #isComplete()} states the same thing
 * to a caller that wants to know whether the scan covered everything it meant to.
 */
public final class Report {
    private final List<Finding> findings;

    private Report(List<Finding> findings) {
        this.findings = Collections.unmodifiableList(findings);
    }

    public static Report of(List<Finding> findings) {
        Set<String> seen = new HashSet<String>();
        List<Finding> copy = new ArrayList<Finding>(findings.size());
        for (Finding finding : findings) {
            if (!seen.add(finding.probeId())) {
                throw new IllegalArgumentException("duplicate probe id: " + finding.probeId());
            }
            copy.add(finding);
        }
        return new Report(copy);
    }

    public List<Finding> findings() {
        return findings;
    }

    public Status verdict() {
        Status worst = Status.PASS;
        for (Finding finding : findings) {
            worst = Status.worst(worst, finding.status());
        }
        return worst;
    }

    public int count(Status status) {
        int n = 0;
        for (Finding finding : findings) {
            if (finding.status() == status) {
                n++;
            }
        }
        return n;
    }

    /** False when at least one probe could not observe its signal. */
    public boolean isComplete() {
        return count(Status.UNKNOWN) == 0;
    }

    /** One ASCII line, stable enough to diff two scans byte for byte. */
    public String summary() {
        return "PASS=" + count(Status.PASS)
                + " INFO=" + count(Status.INFO)
                + " WARN=" + count(Status.WARN)
                + " FAIL=" + count(Status.FAIL)
                + " UNKNOWN=" + count(Status.UNKNOWN)
                + " verdict=" + verdict();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(summary());
        for (Finding finding : findings) {
            out.append('\n').append(finding);
        }
        return out.toString();
    }
}
