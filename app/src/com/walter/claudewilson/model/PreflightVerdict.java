package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure pre-flight launch readiness evaluator (GO / CAUTION / BLOCKED).
 *
 * <p>Aggregates live persona, network, store, process, and residue facts into a single
 * prioritized verdict and one-tap primary remediation action. Zero Android imports, zero CJK.
 */
public final class PreflightVerdict {

    public enum Level {
        READY,
        CAUTION,
        BLOCKED
    }

    public enum Severity {
        BLOCKER,
        WARNING
    }

    public enum Code {
        NATIVE_RESTRICTED_SIM,
        TARGET_RUNNING,
        EGRESS_RESTRICTED,
        PERSONA_DRIFT,
        EGRESS_MISMATCH,
        NETWORK_LEAK,
        DATACENTER_IP,
        STORE_MISMATCH,
        EGRESS_UNPROBED
    }

    public enum PrimaryAction {
        NONE,
        STOP_TARGET,
        ALIGN_PERSONA,
        PICK_REGION,
        ALIGN_EGRESS,
        PROBE_NETWORK
    }

    public static final class Issue {
        public final Severity severity;
        public final Code code;
        public final String detail;

        public Issue(Severity severity, Code code, String detail) {
            this.severity = severity;
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Input {
        public final boolean hasPersona;
        public final String regionIso;
        public final int personaMismatches;
        public final boolean restrictedNativeSim;
        public final boolean targetRunning;
        public final boolean egressProbed;
        public final String egressIso;
        public final boolean egressRestricted;
        public final boolean egressDatacenter;
        public final boolean networkLeak;
        public final String playCountry;

        public Input(boolean hasPersona, String regionIso, int personaMismatches,
                     boolean restrictedNativeSim, boolean targetRunning,
                     boolean egressProbed, String egressIso,
                     boolean egressRestricted, boolean egressDatacenter,
                     boolean networkLeak, String playCountry) {
            this.hasPersona = hasPersona;
            this.regionIso = regionIso == null ? "" : regionIso.trim().toUpperCase(Locale.ROOT);
            this.personaMismatches = Math.max(0, personaMismatches);
            this.restrictedNativeSim = restrictedNativeSim;
            this.targetRunning = targetRunning;
            this.egressProbed = egressProbed;
            this.egressIso = egressIso == null ? "" : egressIso.trim().toUpperCase(Locale.ROOT);
            this.egressRestricted = egressRestricted;
            this.egressDatacenter = egressDatacenter;
            this.networkLeak = networkLeak;
            this.playCountry = playCountry == null ? "" : playCountry.trim().toUpperCase(Locale.ROOT);
        }
    }

    public final Level level;
    public final List<Issue> issues;
    public final int blockerCount;
    public final int warningCount;
    public final PrimaryAction primaryAction;
    public final boolean targetRunning;

    private PreflightVerdict(Level level, List<Issue> issues, int blockerCount, int warningCount,
                             PrimaryAction primaryAction, boolean targetRunning) {
        this.level = level;
        this.issues = Collections.unmodifiableList(issues);
        this.blockerCount = blockerCount;
        this.warningCount = warningCount;
        this.primaryAction = primaryAction;
        this.targetRunning = targetRunning;
    }

    public static PreflightVerdict evaluate(Input in) {
        List<Issue> blockers = new ArrayList<Issue>();
        List<Issue> warnings = new ArrayList<Issue>();

        boolean nativeRestricted = !in.hasPersona && in.restrictedNativeSim;
        boolean egressRestricted = in.egressProbed && in.egressRestricted;
        boolean personaDrift = in.hasPersona && in.personaMismatches > 0;
        boolean activeLeak = in.egressProbed && in.networkLeak;

        // A running target is normal once its persona is cleanly applied; only block and
        // require force-stop when it is actively running in a hazardous or drifted state.
        if (in.targetRunning && (nativeRestricted || egressRestricted || personaDrift || activeLeak)) {
            blockers.add(new Issue(Severity.BLOCKER, Code.TARGET_RUNNING, ""));
        }
        if (nativeRestricted) {
            blockers.add(new Issue(Severity.BLOCKER, Code.NATIVE_RESTRICTED_SIM, ""));
        }
        if (egressRestricted) {
            blockers.add(new Issue(Severity.BLOCKER, Code.EGRESS_RESTRICTED, in.egressIso));
        }
        if (personaDrift) {
            blockers.add(new Issue(Severity.BLOCKER, Code.PERSONA_DRIFT,
                    String.valueOf(in.personaMismatches)));
        }

        if (in.egressProbed) {
            if (in.hasPersona && !in.egressIso.isEmpty() && !in.regionIso.isEmpty()
                    && !in.egressIso.equalsIgnoreCase(in.regionIso) && !in.egressRestricted) {
                warnings.add(new Issue(Severity.WARNING, Code.EGRESS_MISMATCH,
                        in.egressIso + "->" + in.regionIso));
            }
            if (in.networkLeak) {
                warnings.add(new Issue(Severity.WARNING, Code.NETWORK_LEAK, ""));
            }
            if (in.egressDatacenter) {
                warnings.add(new Issue(Severity.WARNING, Code.DATACENTER_IP, in.egressIso));
            }
        } else if (in.hasPersona) {
            warnings.add(new Issue(Severity.WARNING, Code.EGRESS_UNPROBED, ""));
        }

        if (in.hasPersona && in.playCountry.matches("[A-Z]{2}")
                && !in.regionIso.isEmpty() && !in.playCountry.equalsIgnoreCase(in.regionIso)) {
            warnings.add(new Issue(Severity.WARNING, Code.STORE_MISMATCH,
                    in.playCountry + "->" + in.regionIso));
        }

        List<Issue> all = new ArrayList<Issue>(blockers.size() + warnings.size());
        all.addAll(blockers);
        all.addAll(warnings);

        Level level = !blockers.isEmpty() ? Level.BLOCKED
                : !warnings.isEmpty() ? Level.CAUTION
                : Level.READY;

        PrimaryAction action = pickAction(in, blockers, warnings);
        return new PreflightVerdict(level, all, blockers.size(), warnings.size(), action, in.targetRunning);
    }

    private static PrimaryAction pickAction(Input in, List<Issue> blockers, List<Issue> warnings) {
        for (Issue i : blockers) {
            if (i.code == Code.TARGET_RUNNING) return PrimaryAction.STOP_TARGET;
        }
        for (Issue i : blockers) {
            if (i.code == Code.PERSONA_DRIFT) return PrimaryAction.ALIGN_PERSONA;
        }
        for (Issue i : blockers) {
            if (i.code == Code.NATIVE_RESTRICTED_SIM) return PrimaryAction.PICK_REGION;
        }
        for (Issue i : blockers) {
            if (i.code == Code.EGRESS_RESTRICTED) return PrimaryAction.PROBE_NETWORK;
        }
        for (Issue i : warnings) {
            if (i.code == Code.EGRESS_MISMATCH) return PrimaryAction.ALIGN_EGRESS;
        }
        for (Issue i : warnings) {
            if (i.code == Code.EGRESS_UNPROBED) return PrimaryAction.PROBE_NETWORK;
        }
        return PrimaryAction.NONE;
    }
}
