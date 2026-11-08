package com.walter.claudewilson.model;

/**
 * Machine-readable cause of a {@link Status}. Renderers map codes to prose; the domain layer
 * carries no user-facing copy, so the same finding can be rendered in any language and the
 * wording stays out of the logic that decides it.
 */
public enum Reason {
    /** Observed and nothing to report. */
    OK,
    /** Expected artifact was not found. */
    ABSENT,
    /** Artifact was found. */
    PRESENT,
    /** The signal could not be read at all. */
    UNREADABLE,
    /** Readable, but not in a shape the check understands. */
    UNPARSEABLE,
    /** Two sources of the same fact disagree. */
    INCONSISTENT,
    /** The facility exists but refused or could not run (key generation failed, feature absent). */
    NOT_AVAILABLE,
    /** Only part of the evidence was produced, so the conclusion cannot be total. */
    INCOMPLETE,
    /** Build signed with test keys. */
    TEST_KEYS,
    /** Required reference data (catalog) is missing, so nothing was compared. */
    NO_CATALOG,
    /** No SIM present. */
    NO_SIM,
    /** Carrier name does not match the MNC/MCC it is paired with. */
    CARRIER_UNKNOWN,
    /** Heuristic hit that needs a human decision. */
    SUSPICIOUS
}
