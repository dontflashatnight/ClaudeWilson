package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure model for the Target View Bodyguard policy, 8 monitoring gates, and searchable
 * audited-baseline catalog.
 */
public final class BodyguardPolicy {

    public static final String MODE_OFF = "OFF";
    public static final String MODE_OBSERVE = "OBSERVE";
    public static final String MODE_BLOCK_KILL = "BLOCK_KILL";

    public static final String GATE_IP_FLIP = "IP_FLIP";
    public static final String GATE_TELEMETRY_KEY = "TELEMETRY_KEY";
    public static final String GATE_SYS_PROP = "SYS_PROP";
    public static final String GATE_SETTINGS_KEY = "SETTINGS_KEY";
    public static final String GATE_TRIPWIRE_API = "TRIPWIRE_API";
    public static final String GATE_DYNAMIC_CODE = "DYNAMIC_CODE";
    public static final String GATE_SENSITIVE_PATH = "SENSITIVE_PATH";
    public static final String GATE_EGRESS_HOST = "EGRESS_HOST";
    /** Fail-closed startup guard when target launches without an active persona baseline. */
    public static final String GATE_NO_PERSONA = "NO_PERSONA";

    public static final String STATUS_HOOKED = "HOOKED";
    public static final String STATUS_TRIPWIRE = "TRIPWIRE";
    public static final String STATUS_EXEMPT = "EXEMPT";
    public static final String STATUS_CUSTOM = "CUSTOM";

    public static final List<String> ALL_GATES = Collections.unmodifiableList(Arrays.asList(
            GATE_IP_FLIP,
            GATE_TELEMETRY_KEY,
            GATE_SYS_PROP,
            GATE_SETTINGS_KEY,
            GATE_TRIPWIRE_API,
            GATE_DYNAMIC_CODE,
            GATE_SENSITIVE_PATH,
            GATE_EGRESS_HOST));

    private BodyguardPolicy() {
    }

    public static final class SignalEntry {
        public final String key;
        public final String gate;
        public final String status;
        public final String owner;
        public final String version;
        public final boolean custom;

        public SignalEntry(String key, String gate, String status, String owner,
                           String version, boolean custom) {
            this.key = nn(key);
            this.gate = nn(gate);
            this.status = nn(status);
            this.owner = nn(owner);
            this.version = nn(version);
            this.custom = custom;
        }
    }

    public static final class TargetBaseline {
        public final String targetId;
        public final long versionCode;
        public final String versionName;
        public final List<SignalEntry> entries;

        public TargetBaseline(String targetId, long versionCode, String versionName,
                              List<SignalEntry> entries) {
            this.targetId = nn(targetId);
            this.versionCode = versionCode;
            this.versionName = nn(versionName);
            this.entries = Collections.unmodifiableList(
                    entries == null ? Collections.<SignalEntry>emptyList() : entries);
        }
    }

    /** Normalizes any stored mode string, defaulting to OBSERVE on first upgrade. */
    public static String normalizeMode(String raw) {
        if (MODE_OFF.equals(raw) || MODE_OBSERVE.equals(raw) || MODE_BLOCK_KILL.equals(raw)) {
            return raw;
        }
        return MODE_OBSERVE;
    }

    /** Filters baseline + custom entries by gate/CUSTOM filter and case-insensitive substring. */
    public static List<SignalEntry> filter(List<SignalEntry> entries,
                                           String gateOrCustomFilter, String query) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        String f = nn(gateOrCustomFilter).trim();
        String q = nn(query).trim().toLowerCase(Locale.ROOT);
        List<SignalEntry> out = new ArrayList<SignalEntry>();
        for (SignalEntry e : entries) {
            if ("CUSTOM".equals(f)) {
                if (!e.custom) {
                    continue;
                }
            } else if (!f.isEmpty() && !"ALL".equals(f)) {
                if (!f.equals(e.gate)) {
                    continue;
                }
            }
            if (!q.isEmpty()) {
                boolean hit = e.key.toLowerCase(Locale.ROOT).contains(q)
                        || e.owner.toLowerCase(Locale.ROOT).contains(q)
                        || e.gate.toLowerCase(Locale.ROOT).contains(q);
                if (!hit) {
                    continue;
                }
            }
            out.add(e);
        }
        return out;
    }

    /** Counts signals per gate in canonical ALL_GATES order. */
    public static Map<String, Integer> gateCounts(List<SignalEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String gate : ALL_GATES) {
            counts.put(gate, Integer.valueOf(0));
        }
        if (entries != null) {
            for (SignalEntry e : entries) {
                Integer cur = counts.get(e.gate);
                counts.put(e.gate, Integer.valueOf(cur == null ? 1 : cur.intValue() + 1));
            }
        }
        return counts;
    }

    /**
     * Pure lock-state decision shared with {@code SystemBodyguardHooks} inside {@code system_server}.
     * A lock from either in-memory auto-lock or persisted {@code .lock} file remains active until a
     * newer {@code .unlock} timestamp supersedes it.
     */
    public static boolean isLockEffective(long memoryLockedAtMs, long lockFileTimeMs,
                                          long unlockFileTimeMs) {
        long effectiveLock = Math.max(memoryLockedAtMs, lockFileTimeMs);
        return effectiveLock > 0L && effectiveLock > unlockFileTimeMs;
    }

    /**
     * Returns true only when ClaudeWilson has a previously recorded install stamp and the current
     * APK install stamp differs (i.e. an in-place upgrade occurred, not a fresh install).
     */
    public static boolean isAppStampChanged(String previousStamp, String currentStamp) {
        String prev = nn(previousStamp).trim();
        String curr = nn(currentStamp).trim();
        return !prev.isEmpty() && !curr.isEmpty() && !prev.equals(curr);
    }

    /** Parses the standalone {@code guard.mode} sentinel file; empty/unknown defaults to OFF. */
    public static String parseGuardMode(String raw) {
        String s = nn(raw).trim();
        if (MODE_BLOCK_KILL.equals(s) || MODE_OBSERVE.equals(s) || MODE_OFF.equals(s)) {
            return s;
        }
        return MODE_OFF;
    }

    /** True when Bodyguard must fail-closed (kill target before init) if no persona is active. */
    public static boolean shouldFailClosedWithoutPersona(String guardMode) {
        return MODE_BLOCK_KILL.equals(parseGuardMode(guardMode));
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
