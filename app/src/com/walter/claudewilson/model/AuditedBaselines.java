package com.walter.claudewilson.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audited target-app baseline versions exported by {@code tools/target_watch/watch.py}.
 *
 * <p>Pure model logic: compares an installed target's version against the last audited baseline
 * without touching Android APIs or launching the target.
 */
public final class AuditedBaselines {
    public enum Drift {
        MATCHED,
        UPDATED,
        OLDER,
        UNAUDITED,
        UNKNOWN
    }

    public static final class Baseline {
        private final String targetId;
        private final long versionCode;
        private final String versionName;

        public Baseline(String targetId, long versionCode, String versionName) {
            this.targetId = targetId == null ? "" : targetId.trim();
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName.trim();
        }

        public String targetId() {
            return targetId;
        }

        public long versionCode() {
            return versionCode;
        }

        public String versionName() {
            return versionName;
        }
    }

    private AuditedBaselines() {
    }

    /**
     * Parses {@code audited-targets.txt} lines of the form {@code targetId=versionCode|versionName}.
     * Comments ({@code #}), blank lines, and malformed entries are skipped safely.
     */
    public static Map<String, Baseline> parse(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Baseline> out = new LinkedHashMap<String, Baseline>();
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) {
                continue;
            }
            String id = line.substring(0, eq).trim();
            String rest = line.substring(eq + 1).trim();
            if (id.isEmpty() || rest.isEmpty()) {
                continue;
            }
            int bar = rest.indexOf('|');
            String codePart = bar >= 0 ? rest.substring(0, bar).trim() : rest;
            String namePart = bar >= 0 ? rest.substring(bar + 1).trim() : "";
            long code;
            try {
                code = Long.parseLong(codePart);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (code <= 0L) {
                continue;
            }
            out.put(id, new Baseline(id, code, namePart));
        }
        return Collections.unmodifiableMap(out);
    }

    /** Compares an installed target package against its last audited baseline. */
    public static Drift assess(boolean installed, long installedCode, String installedName,
                               Baseline baseline) {
        if (!installed) {
            return Drift.UNKNOWN;
        }
        if (baseline == null || baseline.versionCode() <= 0L) {
            return Drift.UNAUDITED;
        }
        if (installedCode > 0L) {
            if (installedCode > baseline.versionCode()) {
                return Drift.UPDATED;
            }
            if (installedCode < baseline.versionCode()) {
                return Drift.OLDER;
            }
            return Drift.MATCHED;
        }
        String currName = installedName == null ? "" : installedName.trim();
        String baseName = baseline.versionName();
        if (!currName.isEmpty() && !baseName.isEmpty()) {
            return currName.equals(baseName) ? Drift.MATCHED : Drift.UPDATED;
        }
        return Drift.UNKNOWN;
    }
}
