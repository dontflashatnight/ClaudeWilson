package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure matching engine for the Target View Persona-Match Ledger ("report").
 *
 * <p>Compares persisted historical observations read passively from target telemetry stores
 * against the active persona snapshot. Flags:
 * <ul>
 *   <li>{@link #VERDICT_RESTRICTED_LEAK}: any historical upload carrying a restricted ISO or MCC
 *       from {@code catalog/restricted-regions.txt}.</li>
 *   <li>{@link #VERDICT_TAINTED_IDENTITY}: a persistent SDK/device ID whose {@code firstSeen}
 *       predates or coincides with the latest restricted leak (identity chain still carries prior
 *       taint even if the region persona was later fixed).</li>
 *   <li>{@link #VERDICT_DRIFT}: latest uploaded value disagrees with the current persona.</li>
 *   <li>{@link #VERDICT_MATCH}: coherent with the current persona and clean of restricted history.</li>
 * </ul>
 */
public final class PersonaMatchEngine {

    public static final String GROUP_GEO = "geo";
    public static final String GROUP_HARDWARE = "hardware";
    public static final String GROUP_IDENTITY = "identity";

    public static final String VERDICT_MATCH = "MATCH";
    public static final String VERDICT_DRIFT = "DRIFT";
    public static final String VERDICT_RESTRICTED_LEAK = "RESTRICTED_LEAK";
    public static final String VERDICT_TAINTED_IDENTITY = "TAINTED_IDENTITY";
    public static final String VERDICT_UNOBSERVED = "UNOBSERVED";

    private static final List<String> GEO_KEYS = Collections.unmodifiableList(Arrays.asList(
            "country", "mcc_mnc", "locale", "tz_offset", "play_country", "country_code_hint"));

    private static final List<String> HARDWARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "model", "manufacturer", "device", "product", "hardware",
            "fingerprint", "os_build", "sdk_version"));

    private static final List<String> CLAUDE_ID_KEYS = Collections.unmodifiableList(Arrays.asList(
            "device_id", "growthbook_unit", "segment_anonymous", "segment_device",
            "sift_installation", "sift_fingerprint", "sentry_installation",
            "datadog_anonymous", "firebase_fid"));

    private static final List<String> CHATGPT_ID_KEYS = Collections.unmodifiableList(Arrays.asList(
            "device_uuid", "block_seed", "statsig_stable", "segment_anonymous",
            "segment_device", "sentry_installation", "datadog_anonymous", "firebase_fid"));

    private static final Set<String> ALL_ID_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "device_id", "device_uuid", "block_seed", "growthbook_unit",
                    "statsig_stable", "segment_anonymous", "segment_device",
                    "sift_installation", "sift_fingerprint", "sentry_installation",
                    "datadog_anonymous", "firebase_fid")));

    private PersonaMatchEngine() {
    }

    public static final class PersonaSnapshot {
        public final long id;
        public final String targetId;
        public final String userId;
        public final long createdAt;
        public final String regionIso;
        public final String simOp;
        public final String simIso;
        public final String simCarrier;
        public final boolean keepRealSim;
        public final String netOp;
        public final String netIso;
        public final String tz;
        public final int utcOffsetSeconds;
        public final boolean hasUtcOffset;
        public final String locale;
        public final String model;
        public final String manufacturer;
        public final String device;
        public final String product;
        public final String hardware;
        public final String fingerprint;
        public final String osBuild;
        public final String sdkInt;
        public final String androidId;
        public final String ipv6Prefix;
        public final String egressCountry;
        public final String locationCity;
        public final String digest;

        public PersonaSnapshot(long id, String targetId, String userId, long createdAt,
                               String regionIso, String simOp, String simIso, String simCarrier,
                               boolean keepRealSim, String netOp, String netIso,
                               String tz, int utcOffsetSeconds, boolean hasUtcOffset,
                               String locale, String model, String manufacturer,
                               String device, String product, String hardware,
                               String fingerprint, String osBuild, String sdkInt,
                               String androidId, String ipv6Prefix, String egressCountry,
                               String locationCity, String digest) {
            this.id = id;
            this.targetId = nn(targetId);
            this.userId = nn(userId);
            this.createdAt = createdAt;
            this.regionIso = nn(regionIso).toLowerCase(Locale.ROOT);
            this.simOp = nn(simOp);
            this.simIso = nn(simIso).toLowerCase(Locale.ROOT);
            this.simCarrier = nn(simCarrier);
            this.keepRealSim = keepRealSim;
            this.netOp = nn(netOp);
            this.netIso = nn(netIso).toLowerCase(Locale.ROOT);
            this.tz = nn(tz);
            this.utcOffsetSeconds = utcOffsetSeconds;
            this.hasUtcOffset = hasUtcOffset;
            this.locale = nn(locale);
            this.model = nn(model);
            this.manufacturer = nn(manufacturer);
            this.device = nn(device);
            this.product = nn(product);
            this.hardware = nn(hardware);
            this.fingerprint = nn(fingerprint);
            this.osBuild = nn(osBuild);
            this.sdkInt = nn(sdkInt);
            this.androidId = nn(androidId);
            this.ipv6Prefix = nn(ipv6Prefix);
            this.egressCountry = nn(egressCountry).toLowerCase(Locale.ROOT);
            this.locationCity = nn(locationCity);
            this.digest = nn(digest);
        }
    }

    public static final class UploadRecord {
        public final String fieldKey;
        public final String observedValue;
        public final String sourceSdk;
        public final long firstSeen;
        public final long lastSeen;
        public final int seenCount;
        public final long personaSnapshotId;

        public UploadRecord(String fieldKey, String observedValue, String sourceSdk,
                            long firstSeen, long lastSeen, int seenCount, long personaSnapshotId) {
            this.fieldKey = nn(fieldKey);
            this.observedValue = nn(observedValue);
            this.sourceSdk = nn(sourceSdk);
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.seenCount = seenCount < 1 ? 1 : seenCount;
            this.personaSnapshotId = personaSnapshotId;
        }
    }

    public static final class HistoryItem {
        public final String observedValue;
        public final String maskedValue;
        public final String sourceSdk;
        public final long firstSeen;
        public final long lastSeen;
        public final int seenCount;
        public final String verdict;

        public HistoryItem(String observedValue, String maskedValue, String sourceSdk,
                           long firstSeen, long lastSeen, int seenCount, String verdict) {
            this.observedValue = observedValue;
            this.maskedValue = maskedValue;
            this.sourceSdk = sourceSdk;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.seenCount = seenCount;
            this.verdict = verdict;
        }
    }

    public static final class FieldRow {
        public final String fieldKey;
        public final String group;
        public final String expectedValue;
        public final String latestValue;
        public final String maskedLatestValue;
        public final String latestSourceSdk;
        public final long latestSeen;
        public final String verdict;
        public final boolean isIdentityField;
        public final boolean hasRestrictedHistory;
        public final List<HistoryItem> history;

        public FieldRow(String fieldKey, String group, String expectedValue,
                        String latestValue, String maskedLatestValue, String latestSourceSdk,
                        long latestSeen, String verdict, boolean isIdentityField,
                        boolean hasRestrictedHistory, List<HistoryItem> history) {
            this.fieldKey = fieldKey;
            this.group = group;
            this.expectedValue = expectedValue;
            this.latestValue = latestValue;
            this.maskedLatestValue = maskedLatestValue;
            this.latestSourceSdk = latestSourceSdk;
            this.latestSeen = latestSeen;
            this.verdict = verdict;
            this.isIdentityField = isIdentityField;
            this.hasRestrictedHistory = hasRestrictedHistory;
            this.history = Collections.unmodifiableList(history);
        }
    }

    public static final class MatchReport {
        public final List<FieldRow> rows;
        public final int totalEvaluated;
        public final int matchedCount;
        public final int leakCount;
        public final int taintedCount;
        public final int driftCount;
        public final int unobservedCount;
        public final int matchPercent;
        public final long latestRestrictedLeakAt;

        public MatchReport(List<FieldRow> rows, int totalEvaluated, int matchedCount,
                           int leakCount, int taintedCount, int driftCount,
                           int unobservedCount, int matchPercent, long latestRestrictedLeakAt) {
            this.rows = Collections.unmodifiableList(rows);
            this.totalEvaluated = totalEvaluated;
            this.matchedCount = matchedCount;
            this.leakCount = leakCount;
            this.taintedCount = taintedCount;
            this.driftCount = driftCount;
            this.unobservedCount = unobservedCount;
            this.matchPercent = matchPercent;
            this.latestRestrictedLeakAt = latestRestrictedLeakAt;
        }
    }

    /** Masks long identifier values (UUID/hex tokens) to an 8-character prefix + ellipsis. */
    public static String maskIdentity(String raw) {
        String s = nn(raw).trim();
        if (s.length() <= 10) {
            return s;
        }
        return s.substring(0, 8) + "...";
    }

    /** True when {@code fieldKey} represents a persistent SDK/device identifier. */
    public static boolean isIdentityKey(String fieldKey) {
        return ALL_ID_KEYS.contains(nn(fieldKey));
    }

    /**
     * Checks whether a single uploaded {@code (fieldKey, value)} constitutes a restricted
     * country/MCC leak against {@code catalog/restricted-regions.txt}.
     */
    public static boolean isRestrictedValue(Tables tables, String targetId,
                                            String fieldKey, String value) {
        if (tables == null || tables.restrictedRegions().isEmpty()) {
            return false;
        }
        String k = nn(fieldKey);
        String v = nn(value).trim();
        if (v.isEmpty()) {
            return false;
        }
        if ("country".equals(k) || "play_country".equals(k) || "country_code_hint".equals(k)) {
            return SimPolicy.isRestrictedIso(tables, v, targetId);
        }
        if ("mcc_mnc".equals(k)) {
            return SimPolicy.isRestrictedOperator(tables, v, targetId);
        }
        if ("locale".equals(k)) {
            String iso = localeCountry(v);
            return !iso.isEmpty() && SimPolicy.isRestrictedIso(tables, iso, targetId);
        }
        return false;
    }

    /**
     * Evaluates historical uploaded observations against the expected persona snapshot.
     *
     * <p>Rows are returned sorted by severity:
     * {@code RESTRICTED_LEAK -> TAINTED_IDENTITY -> DRIFT -> UNOBSERVED -> MATCH}.
     */
    public static MatchReport evaluate(Tables tables, String targetId,
                                       PersonaSnapshot expected, List<UploadRecord> records) {
        Map<String, List<UploadRecord>> byKey = new LinkedHashMap<String, List<UploadRecord>>();
        long latestRestrictedLeakAt = 0L;

        if (records != null) {
            for (UploadRecord rec : records) {
                if (rec == null || rec.fieldKey.isEmpty() || rec.observedValue.isEmpty()) {
                    continue;
                }
                List<UploadRecord> list = byKey.get(rec.fieldKey);
                if (list == null) {
                    list = new ArrayList<UploadRecord>();
                    byKey.put(rec.fieldKey, list);
                }
                list.add(rec);
                if (isRestrictedValue(tables, targetId, rec.fieldKey, rec.observedValue)) {
                    if (rec.lastSeen > latestRestrictedLeakAt) {
                        latestRestrictedLeakAt = rec.lastSeen;
                    }
                }
            }
        }

        // Sort each field's observations newest-first.
        Comparator<UploadRecord> newestFirst = new Comparator<UploadRecord>() {
            @Override public int compare(UploadRecord a, UploadRecord b) {
                int c = Long.compare(b.lastSeen, a.lastSeen);
                return c != 0 ? c : Long.compare(b.firstSeen, a.firstSeen);
            }
        };
        for (List<UploadRecord> list : byKey.values()) {
            Collections.sort(list, newestFirst);
        }

        List<String> orderedKeys = orderedKeysFor(targetId, byKey.keySet());
        List<FieldRow> rows = new ArrayList<FieldRow>(orderedKeys.size());

        int totalEvaluated = 0;
        int matchedCount = 0;
        int leakCount = 0;
        int taintedCount = 0;
        int driftCount = 0;
        int unobservedCount = 0;

        for (String key : orderedKeys) {
            String group = groupFor(key);
            boolean isId = isIdentityKey(key);
            String exp = expectedFor(expected, key);
            List<UploadRecord> recs = byKey.get(key);

            if (recs == null || recs.isEmpty()) {
                // Only emit unobserved rows for core geo/hardware fields that have an expected value.
                if (!isId && !exp.isEmpty()) {
                    rows.add(new FieldRow(key, group, exp, "", "", "", 0L,
                            VERDICT_UNOBSERVED, false, false,
                            Collections.<HistoryItem>emptyList()));
                    unobservedCount++;
                }
                continue;
            }

            boolean anyRestricted = false;
            List<HistoryItem> hist = new ArrayList<HistoryItem>(recs.size());
            for (UploadRecord r : recs) {
                String itemVerdict = evaluateSingle(tables, targetId, key, exp, expected,
                        r, isId, latestRestrictedLeakAt);
                if (VERDICT_RESTRICTED_LEAK.equals(itemVerdict)) {
                    anyRestricted = true;
                }
                String masked = isId ? maskIdentity(r.observedValue) : r.observedValue;
                hist.add(new HistoryItem(r.observedValue, masked, r.sourceSdk,
                        r.firstSeen, r.lastSeen, r.seenCount, itemVerdict));
            }

            UploadRecord latest = recs.get(0);
            String latestVerdict = hist.get(0).verdict;
            // Historical restricted leak escalates the row even if a newer upload looks clean.
            String rowVerdict = anyRestricted ? VERDICT_RESTRICTED_LEAK : latestVerdict;

            totalEvaluated++;
            if (VERDICT_RESTRICTED_LEAK.equals(rowVerdict)) {
                leakCount++;
            } else if (VERDICT_TAINTED_IDENTITY.equals(rowVerdict)) {
                taintedCount++;
            } else if (VERDICT_DRIFT.equals(rowVerdict)) {
                driftCount++;
            } else {
                matchedCount++;
            }

            String expShown = isId
                    ? (VERDICT_TAINTED_IDENTITY.equals(rowVerdict) ? "reset" : "clean")
                    : exp;
            String maskedLatest = isId ? maskIdentity(latest.observedValue) : latest.observedValue;

            rows.add(new FieldRow(key, group, expShown, latest.observedValue, maskedLatest,
                    latest.sourceSdk, latest.lastSeen, rowVerdict, isId, anyRestricted, hist));
        }

        Collections.sort(rows, new Comparator<FieldRow>() {
            @Override public int compare(FieldRow a, FieldRow b) {
                int sa = severity(a.verdict);
                int sb = severity(b.verdict);
                if (sa != sb) {
                    return Integer.compare(sa, sb);
                }
                return Long.compare(b.latestSeen, a.latestSeen);
            }
        });

        int pct = totalEvaluated == 0 ? 100
                : (int) Math.round((matchedCount * 100.0d) / totalEvaluated);

        return new MatchReport(rows, totalEvaluated, matchedCount, leakCount, taintedCount,
                driftCount, unobservedCount, pct, latestRestrictedLeakAt);
    }

    /** Renders a compact, offline Markdown summary (no raw tokens/cookies). */
    public static String exportMarkdown(MatchReport report, PersonaSnapshot expected,
                                        boolean maskIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ClaudeWilson Persona-Match Report\n\n");
        if (expected != null) {
            sb.append("- Target: `").append(expected.targetId).append("`\n");
            sb.append("- Space: `u").append(expected.userId).append("`\n");
            sb.append("- Persona: `").append(expected.regionIso).append("` / SIM `")
                    .append(expected.simOp).append("`")
                    .append(expected.keepRealSim ? " (real-sim)" : "")
                    .append(" / `").append(expected.locale).append("` / `")
                    .append(expected.model).append("`\n");
        }
        sb.append("- Score: **").append(report.matchPercent).append("%** (")
                .append(report.matchedCount).append('/').append(report.totalEvaluated)
                .append(", leaks=").append(report.leakCount)
                .append(", tainted=").append(report.taintedCount)
                .append(", drift=").append(report.driftCount).append(")\n\n");
        sb.append("| Field | Group | Expected | Latest Uploaded | Source | Verdict |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (FieldRow r : report.rows) {
            String val = (r.isIdentityField && maskIds) ? r.maskedLatestValue : r.latestValue;
            sb.append("| `").append(r.fieldKey).append("` | ").append(r.group)
                    .append(" | `").append(r.expectedValue.isEmpty() ? "-" : r.expectedValue)
                    .append("` | `").append(val.isEmpty() ? "-" : val)
                    .append("` | ").append(r.latestSourceSdk.isEmpty() ? "-" : r.latestSourceSdk)
                    .append(" | ").append(r.verdict).append(" |\n");
        }
        return sb.toString();
    }

    private static String evaluateSingle(Tables tables, String targetId, String key,
                                         String expectedValue, PersonaSnapshot snap,
                                         UploadRecord rec, boolean isId,
                                         long latestRestrictedLeakAt) {
        if (isId) {
            if (latestRestrictedLeakAt > 0L && rec.firstSeen > 0L
                    && rec.firstSeen <= latestRestrictedLeakAt) {
                return VERDICT_TAINTED_IDENTITY;
            }
            return VERDICT_MATCH;
        }
        if (isRestrictedValue(tables, targetId, key, rec.observedValue)) {
            return VERDICT_RESTRICTED_LEAK;
        }
        if (snap == null || expectedValue.isEmpty()) {
            return VERDICT_MATCH;
        }
        if ("country".equals(key)) {
            if (rec.observedValue.equalsIgnoreCase(snap.regionIso)) {
                return VERDICT_MATCH;
            }
            if (snap.keepRealSim && !snap.simIso.isEmpty()
                    && rec.observedValue.equalsIgnoreCase(snap.simIso)) {
                return VERDICT_MATCH;
            }
            return VERDICT_DRIFT;
        }
        if ("play_country".equals(key) || "country_code_hint".equals(key)) {
            return rec.observedValue.equalsIgnoreCase(snap.regionIso)
                    ? VERDICT_MATCH : VERDICT_DRIFT;
        }
        if ("mcc_mnc".equals(key)) {
            return rec.observedValue.trim().equals(snap.simOp.trim())
                    ? VERDICT_MATCH : VERDICT_DRIFT;
        }
        if ("locale".equals(key)) {
            return localeMatches(rec.observedValue, snap.locale)
                    ? VERDICT_MATCH : VERDICT_DRIFT;
        }
        if ("tz_offset".equals(key)) {
            if (!snap.hasUtcOffset) {
                return VERDICT_MATCH;
            }
            Integer obsSec = parseOffsetSeconds(rec.observedValue);
            if (obsSec == null) {
                return VERDICT_MATCH;
            }
            int diff = Math.abs(obsSec.intValue() - snap.utcOffsetSeconds);
            return diff <= 3600 ? VERDICT_MATCH : VERDICT_DRIFT;
        }
        return rec.observedValue.trim().equalsIgnoreCase(expectedValue.trim())
                ? VERDICT_MATCH : VERDICT_DRIFT;
    }

    private static String expectedFor(PersonaSnapshot s, String key) {
        if (s == null) {
            return "";
        }
        if ("country".equals(key)) return s.regionIso;
        if ("play_country".equals(key)) return s.regionIso;
        if ("country_code_hint".equals(key)) return s.regionIso;
        if ("mcc_mnc".equals(key)) return s.simOp;
        if ("locale".equals(key)) return s.locale;
        if ("tz_offset".equals(key)) return s.hasUtcOffset ? formatOffset(s.utcOffsetSeconds) : "";
        if ("model".equals(key)) return s.model;
        if ("manufacturer".equals(key)) return s.manufacturer;
        if ("device".equals(key)) return s.device;
        if ("product".equals(key)) return s.product;
        if ("hardware".equals(key)) return s.hardware;
        if ("fingerprint".equals(key)) return s.fingerprint;
        if ("os_build".equals(key)) return s.osBuild;
        if ("sdk_version".equals(key)) return s.sdkInt;
        return "";
    }

    private static List<String> orderedKeysFor(String targetId, Set<String> observedKeys) {
        List<String> out = new ArrayList<String>();
        out.addAll(GEO_KEYS);
        out.addAll(HARDWARE_KEYS);
        if ("chatgpt".equalsIgnoreCase(nn(targetId))) {
            out.addAll(CHATGPT_ID_KEYS);
        } else {
            out.addAll(CLAUDE_ID_KEYS);
        }
        for (String extra : observedKeys) {
            if (!out.contains(extra)) {
                out.add(extra);
            }
        }
        return out;
    }

    private static String groupFor(String key) {
        if (GEO_KEYS.contains(key)) return GROUP_GEO;
        if (HARDWARE_KEYS.contains(key)) return GROUP_HARDWARE;
        if (ALL_ID_KEYS.contains(key)) return GROUP_IDENTITY;
        return GROUP_GEO;
    }

    private static int severity(String verdict) {
        if (VERDICT_RESTRICTED_LEAK.equals(verdict)) return 0;
        if (VERDICT_TAINTED_IDENTITY.equals(verdict)) return 1;
        if (VERDICT_DRIFT.equals(verdict)) return 2;
        if (VERDICT_UNOBSERVED.equals(verdict)) return 3;
        return 4;
    }

    private static String normalizeLocale(String raw) {
        return nn(raw).trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    /**
     * Compares an uploaded locale against the persona's full BCP-47 tag.
     *
     * <p>Google {@code datatransport} / Firelog only transmits the 2-letter language code
     * ({@code en}, {@code ja}) without a region subtag; a language-only observation therefore
     * matches a full persona locale sharing that language ({@code en-AU}, {@code ja-JP}), while
     * a different language or conflicting country subtag ({@code en-US} vs {@code en-AU}) flags
     * drift.
     */
    static boolean localeMatches(String observed, String expected) {
        String obs = normalizeLocale(observed);
        String exp = normalizeLocale(expected);
        if (obs.isEmpty() || exp.isEmpty()) {
            return true;
        }
        if (obs.equals(exp)) {
            return true;
        }
        String[] obsParts = obs.split("_");
        String[] expParts = exp.split("_");
        if (!obsParts[0].equals(expParts[0])) {
            return false;
        }
        if (obsParts.length == 1 || expParts.length == 1) {
            return true;
        }
        return obsParts[obsParts.length - 1].equals(expParts[expParts.length - 1]);
    }

    private static String localeCountry(String raw) {
        String norm = normalizeLocale(raw);
        String[] parts = norm.split("_");
        if (parts.length >= 2 && parts[1].length() == 2) {
            return parts[1];
        }
        return "";
    }

    /** Parses integer seconds ("28800", "-28800", "0s") or "UTC+8" / "+08:00" strings. */
    public static Integer parseOffsetSeconds(String raw) {
        String s = nn(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.endsWith("s") || s.endsWith("S")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException ignored) {
        }
        String u = s.toUpperCase(Locale.ROOT);
        if (u.startsWith("UTC")) {
            u = u.substring(3).trim();
        } else if (u.startsWith("GMT")) {
            u = u.substring(3).trim();
        }
        if (u.isEmpty()) {
            return Integer.valueOf(0);
        }
        int sign = 1;
        if (u.charAt(0) == '+') {
            u = u.substring(1);
        } else if (u.charAt(0) == '-') {
            sign = -1;
            u = u.substring(1);
        }
        String[] parts = u.split(":");
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            return Integer.valueOf(sign * (h * 3600 + m * 60));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String formatOffset(int seconds) {
        String sign = seconds >= 0 ? "+" : "-";
        int abs = Math.abs(seconds);
        int h = abs / 3600;
        int m = (abs % 3600) / 60;
        return String.format(Locale.ROOT, "UTC%s%02d:%02d", sign, h, m);
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
