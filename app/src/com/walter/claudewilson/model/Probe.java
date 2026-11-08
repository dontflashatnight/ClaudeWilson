package com.walter.claudewilson.model;

/**
 * One check over an already-collected {@link Snapshot}.
 *
 * <p>Contract, enforced by {@link Report} and by the self-test suite:
 * <ul>
 *   <li>every probe returns exactly one {@link Finding}, so a check cannot silently emit
 *       nothing;</li>
 *   <li>a probe that lacks its input returns {@link Status#UNKNOWN} with a {@link Reason},
 *       never {@link Status#PASS} -- "I could not look" is not "it is clean";</li>
 *   <li>a probe reads data and decides; it does not format user-facing text.</li>
 * </ul>
 */
public interface Probe {

    /** Stable dotted identifier, also used as the finding key. */
    String id();

    Layer layer();

    /** Name of the upstream signal this probe mirrors; empty when the check has no field. */
    String field();

    Finding run(Snapshot snapshot);
}
