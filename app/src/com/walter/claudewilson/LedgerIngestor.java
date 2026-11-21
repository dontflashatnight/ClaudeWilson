package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;

import com.walter.claudewilson.model.DiagnosticArchiveParser;
import com.walter.claudewilson.model.PersonaMatchEngine;
import com.walter.claudewilson.model.Tables;
import com.walter.claudewilson.platform.Diag;
import com.walter.claudewilson.platform.TablesLoader;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Passive, zero-intrusion ingestor bridging existing read-only collectors and persona snapshots
 * into {@link LedgerDb}.
 *
 * <p>Never touches target directories or target databases directly: it consumes already-parsed
 * maps from {@link TelemetryStore}, {@link ClaudeRiskCollector}, {@link ChatGptRiskCollector}
 * and {@link PersonaPanel}.
 */
final class LedgerIngestor {
    private static final String MIGRATED_PREFS = "ledger_meta";

    private LedgerIngestor() {
    }

    /** Records an expected persona snapshot parsed from the control app's own hook payload JSON. */
    static long recordPersonaFromPayload(Context context, String targetId, String userId,
                                         String payloadJson, String regionIso,
                                         String egressCountry) {
        if (context == null || targetId == null || payloadJson == null || payloadJson.isEmpty()) {
            return 0L;
        }
        String u = normalizeUser(userId);
        try {
            JSONObject root = new JSONObject(payloadJson);
            JSONObject build = root.optJSONObject("build");
            JSONObject loc = root.optJSONObject("location");
            String simOp = root.optString("simOperator", "");
            String simIso = root.optString("simCountryIso", "");
            String simCar = root.optString("simOperatorName", "");
            boolean keepRealSim = root.optBoolean("keepRealSim", false);
            String netOp = root.optString("netOperator", simOp);
            String netIso = root.optString("netCountryIso", simIso);
            String tz = root.optString("tz", "");
            int offsetSec = 0;
            boolean hasOffset = false;
            if (!tz.isEmpty()) {
                TimeZone zone = TimeZone.getTimeZone(tz);
                offsetSec = zone.getOffset(System.currentTimeMillis()) / 1000;
                hasOffset = true;
            }
            String locale = root.optString("locale", "");
            String model = build != null ? build.optString("MODEL", "") : "";
            String manufacturer = build != null ? build.optString("MANUFACTURER", "") : "";
            String device = build != null ? build.optString("DEVICE", "") : "";
            String product = build != null ? build.optString("PRODUCT", "") : "";
            String hardware = build != null ? build.optString("HARDWARE", "") : "";
            String fingerprint = build != null ? build.optString("FINGERPRINT", "") : "";
            String osBuild = build != null ? build.optString("ID", "") : "";
            String sdkInt = build != null && build.has("VERSION.SDK_INT")
                    ? String.valueOf(build.optInt("VERSION.SDK_INT", 0)) : "";
            String aid = root.optString("androidId", "");
            String v6 = root.optString("ipv6Prefix", "");
            String city = loc != null ? loc.optString("city", "") : "";
            String effRegion = regionIso != null && !regionIso.isEmpty() ? regionIso : simIso;
            String egress = egressCountry == null ? "" : egressCountry;

            String digest = sha256Short(targetId + "|" + u + "|" + effRegion + "|" + simOp + "|"
                    + simIso + "|" + simCar + "|" + keepRealSim + "|" + netOp + "|" + netIso + "|"
                    + tz + "|" + locale + "|" + model + "|" + manufacturer + "|" + device + "|"
                    + product + "|" + hardware + "|" + fingerprint + "|" + osBuild + "|" + sdkInt
                    + "|" + aid + "|" + v6 + "|" + egress + "|" + city);

            PersonaMatchEngine.PersonaSnapshot snap = new PersonaMatchEngine.PersonaSnapshot(
                    0L, targetId, u, System.currentTimeMillis(),
                    effRegion, simOp, simIso, simCar, keepRealSim, netOp, netIso,
                    tz, offsetSec, hasOffset, locale, model, manufacturer, device, product,
                    hardware, fingerprint, osBuild, sdkInt, aid, v6, egress, city, digest);
            return LedgerDb.get(context).recordPersonaSnapshot(snap);
        } catch (Throwable t) {
            Diag.warn("ledger", "recordPersonaFromPayload failed", t);
            return 0L;
        }
    }

    /** Records Firelog / datatransport fields ingested by {@link TelemetryStore}. */
    static void ingestTelemetryFields(Context context, String targetId, String userId,
                                      Map<String, String> fields, String source) {
        if (context == null || targetId == null || fields == null || fields.isEmpty()) {
            return;
        }
        String u = normalizeUser(userId);
        migrateLegacyTelemetryOnce(context, targetId, u);
        Map<String, String> mapped = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = canonicalTelemetryKey(e.getKey());
            String val = e.getValue();
            if (key != null && val != null && !val.trim().isEmpty()) {
                mapped.put(key, val.trim());
            }
        }
        if (mapped.isEmpty()) {
            return;
        }
        LedgerDb db = LedgerDb.get(context);
        PersonaMatchEngine.PersonaSnapshot latest = db.latestPersonaSnapshot(targetId, u);
        long snapId = latest != null ? latest.id : 0L;
        Tables tables = TablesLoader.load(context);
        String sdk = "target-db".equals(source) ? "Firelog" : (source == null ? "Firelog" : source);
        db.recordObservations(targetId, u, mapped, sdk, System.currentTimeMillis(), snapId, tables);
    }

    /**
     * Passively ingests all parsed target-side observations from {@link ClaudeRiskCollector}
     * or {@link ChatGptRiskCollector} without running any extra root commands.
     */
    static void ingestCollectorData(Context context, String targetId, String userId,
                                    Map<String, String> data) {
        if (context == null || targetId == null || data == null || data.isEmpty()) {
            return;
        }
        String u = normalizeUser(userId);
        migrateLegacyTelemetryOnce(context, targetId, u);
        LedgerDb db = LedgerDb.get(context);
        PersonaMatchEngine.PersonaSnapshot latest = db.latestPersonaSnapshot(targetId, u);
        long snapId = latest != null ? latest.id : 0L;
        Tables tables = TablesLoader.load(context);
        long now = System.currentTimeMillis();

        // 1. Firelog / Datatransport fields (Claude uses transport_*, ChatGPT uses firebase_*)
        Map<String, String> firelog = new LinkedHashMap<String, String>();
        putFirst(firelog, "country", data.get("transport_country"), data.get("firebase_country"));
        putFirst(firelog, "locale", data.get("transport_locale"), data.get("firebase_locale"));
        putFirst(firelog, "mcc_mnc", data.get("transport_mcc"), data.get("firebase_mcc"));
        putFirst(firelog, "tz_offset", data.get("transport_tz"), data.get("firebase_tz"));
        putFirst(firelog, "fingerprint",
                data.get("transport_fingerprint"), data.get("firebase_fingerprint"));
        putFirst(firelog, "os_build", data.get("transport_os_build"), null);
        putFirst(firelog, "manufacturer", data.get("transport_manufacturer"), null);
        putFirst(firelog, "model", data.get("transport_model"), null);
        putFirst(firelog, "device", data.get("transport_device"), null);
        putFirst(firelog, "product", data.get("transport_product"), null);
        putFirst(firelog, "hardware", data.get("transport_hardware"), null);
        putFirst(firelog, "sdk_version", data.get("transport_sdk_version"), null);
        if (!firelog.isEmpty()) {
            db.recordObservations(targetId, u, firelog, "Firelog", now, snapId, tables);
        }

        // 2. Statsig
        Map<String, String> statsig = new LinkedHashMap<String, String>();
        putIfPresent(statsig, "statsig_stable", data.get("statsig_stable"));
        putIfPresent(statsig, "locale", data.get("statsig_locale"));
        if (!statsig.isEmpty()) {
            db.recordObservations(targetId, u, statsig, "Statsig", now, snapId, tables);
        }

        // 3. Datadog
        Map<String, String> datadog = new LinkedHashMap<String, String>();
        putIfPresent(datadog, "datadog_anonymous", data.get("datadog_anonymous"));
        putIfPresent(datadog, "play_country", data.get("datadog_play_country"));
        if (!datadog.isEmpty()) {
            db.recordObservations(targetId, u, datadog, "Datadog", now, snapId, tables);
        }

        // 4. Segment
        Map<String, String> segment = new LinkedHashMap<String, String>();
        putIfPresent(segment, "segment_anonymous", data.get("segment_anonymous"));
        putIfPresent(segment, "segment_device", data.get("segment_device"));
        if (!segment.isEmpty()) {
            db.recordObservations(targetId, u, segment, "Segment", now, snapId, tables);
        }

        // 5. Sift / Sentry / GrowthBook / Firebase / App local store
        Map<String, String> sift = new LinkedHashMap<String, String>();
        putIfPresent(sift, "sift_installation", data.get("sift_installation"));
        putIfPresent(sift, "sift_fingerprint", data.get("sift_fingerprint"));
        if (!sift.isEmpty()) {
            db.recordObservations(targetId, u, sift, "Sift", now, snapId, tables);
        }

        Map<String, String> sentry = new LinkedHashMap<String, String>();
        putIfPresent(sentry, "sentry_installation", data.get("sentry_installation"));
        if (!sentry.isEmpty()) {
            db.recordObservations(targetId, u, sentry, "Sentry", now, snapId, tables);
        }

        Map<String, String> growthbook = new LinkedHashMap<String, String>();
        putIfPresent(growthbook, "growthbook_unit", data.get("growthbook_unit"));
        if (!growthbook.isEmpty()) {
            db.recordObservations(targetId, u, growthbook, "GrowthBook", now, snapId, tables);
        }

        Map<String, String> firebase = new LinkedHashMap<String, String>();
        putIfPresent(firebase, "firebase_fid", data.get("firebase_fid"));
        if (!firebase.isEmpty()) {
            db.recordObservations(targetId, u, firebase, "Firebase", now, snapId, tables);
        }

        Map<String, String> appLocal = new LinkedHashMap<String, String>();
        putIfPresent(appLocal, "device_id", data.get("device_id"));
        putIfPresent(appLocal, "device_uuid", data.get("device_uuid"));
        putIfPresent(appLocal, "block_seed", data.get("block_seed"));
        putIfPresent(appLocal, "country_code_hint", data.get("country_hint"));
        if (!appLocal.isEmpty()) {
            db.recordObservations(targetId, u, appLocal, "LocalStore", now, snapId, tables);
        }
    }

    /**
     * Passively splits and archives target diagnostic staging caches (Sentry breadcrumbs/tags/
     * envelopes, Datadog RUM last_view_event, and server config/bootstrap response caches) into
     * structured tables inside {@link LedgerDb}. Zero target writes, zero tokens persisted.
     */
    static void ingestDiagnosticArchive(Context context, String targetId, String userId,
                                        Map<String, String> raw) {
        if (context == null || targetId == null || raw == null || raw.isEmpty()) {
            return;
        }
        String u = normalizeUser(userId);
        long now = System.currentTimeMillis();
        DiagnosticArchiveParser.Bundle bundle = DiagnosticArchiveParser.parse(targetId, raw, now);
        LedgerDb db = LedgerDb.get(context);
        if (!bundle.httpEvents.isEmpty()) {
            db.recordHttpEvents(targetId, u, bundle.httpEvents);
        }
        if (!bundle.dimSignals.isEmpty()) {
            db.recordDiagSignals(targetId, u, bundle.dimSignals);
        }
        if (!bundle.datadogLocale.isEmpty()) {
            PersonaMatchEngine.PersonaSnapshot latest = db.latestPersonaSnapshot(targetId, u);
            long snapId = latest != null ? latest.id : 0L;
            Map<String, String> ddLoc = new LinkedHashMap<String, String>();
            ddLoc.put("locale", bundle.datadogLocale);
            db.recordObservations(targetId, u, ddLoc, "Datadog", now, snapId,
                    TablesLoader.load(context));
        }
    }

    static List<DiagnosticArchiveParser.HttpEvent> loadHttpEvents(Context context, String targetId,
                                                                  String userId, int limit) {
        return LedgerDb.get(context).loadHttpEvents(targetId, normalizeUser(userId), limit);
    }

    static List<DiagnosticArchiveParser.DimSignal> loadLatestDiagSignals(Context context,
                                                                         String targetId,
                                                                         String userId) {
        return LedgerDb.get(context).loadLatestDiagSignals(targetId, normalizeUser(userId));
    }

    /**
     * Returns the expected persona snapshot only when a region persona is currently active and
     * matches the latest stored snapshot; returns {@code null} in Native mode so stale prior-region
     * expectations never leak into the Ledger.
     */
    static PersonaMatchEngine.PersonaSnapshot currentExpectedSnapshot(Context context,
                                                                      String targetId,
                                                                      String userId) {
        int idx = RegionSelection.regionIdx;
        if (idx < 0 || idx >= Regions.ALL.size()) {
            return null;
        }
        String activeIso = Regions.ALL.get(idx).iso;
        String u = normalizeUser(userId);
        PersonaMatchEngine.PersonaSnapshot snap = LedgerDb.get(context).latestPersonaSnapshot(targetId, u);
        if (snap == null || snap.regionIso.isEmpty() || !snap.regionIso.equalsIgnoreCase(activeIso)) {
            return null;
        }
        return snap;
    }

    /** Evaluates the current Persona-Match Ledger report for (targetId, userId). */
    static PersonaMatchEngine.MatchReport buildReport(Context context, String targetId,
                                                      String userId) {
        String u = normalizeUser(userId);
        migrateLegacyTelemetryOnce(context, targetId, u);
        LedgerDb db = LedgerDb.get(context);
        PersonaMatchEngine.PersonaSnapshot expected = currentExpectedSnapshot(context, targetId, u);
        List<PersonaMatchEngine.UploadRecord> records = db.loadObservations(targetId, u);
        return PersonaMatchEngine.evaluate(TablesLoader.load(context), targetId, expected, records);
    }

    /** One-time import of pre-ledger TelemetryStore history.jsonl + latest.json into ledger.db. */
    private static void migrateLegacyTelemetryOnce(Context context, String targetId,
                                                   String userId) {
        SharedPreferences prefs = context.getSharedPreferences(MIGRATED_PREFS, 0);
        String flag = "migrated_v1." + targetId + "." + userId;
        if (prefs.getBoolean(flag, false)) {
            return;
        }
        try {
            LedgerDb db = LedgerDb.get(context);
            Tables tables = TablesLoader.load(context);
            File hist = new File(new File(new File(context.getFilesDir(), "telemetry"), targetId),
                    "history.jsonl");
            if (hist.isFile()) {
                String text = new String(Files.readAllBytes(hist.toPath()), StandardCharsets.UTF_8);
                for (String line : text.split("\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        JSONObject row = new JSONObject(trimmed);
                        String key = canonicalTelemetryKey(row.optString("k", ""));
                        String val = row.optString("v", "");
                        long at = row.optLong("at", System.currentTimeMillis());
                        String src = row.optString("source", "Firelog");
                        if (key != null && !val.isEmpty()) {
                            Map<String, String> single = new LinkedHashMap<String, String>();
                            single.put(key, val);
                            db.recordObservations(targetId, userId, single,
                                    "target-db".equals(src) ? "Firelog" : src, at, 0L, tables);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            Map<String, TelemetryStore.Entry> current = TelemetryStore.read(context, targetId);
            for (Map.Entry<String, TelemetryStore.Entry> e : current.entrySet()) {
                String key = canonicalTelemetryKey(e.getKey());
                TelemetryStore.Entry entry = e.getValue();
                if (key != null && entry != null && !entry.value.isEmpty()) {
                    Map<String, String> single = new LinkedHashMap<String, String>();
                    single.put(key, entry.value);
                    String src = "target-db".equals(entry.source) ? "Firelog" : entry.source;
                    db.recordObservations(targetId, userId, single, src,
                            entry.lastSeen > 0L ? entry.lastSeen : System.currentTimeMillis(),
                            0L, tables);
                }
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "legacy telemetry migration failed", t);
        } finally {
            prefs.edit().putBoolean(flag, true).apply();
        }
    }

    private static String canonicalTelemetryKey(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if ("tz-offset".equals(k)) return "tz_offset";
        if ("os-build".equals(k) || "os-uild".equals(k)) return "os_build";
        if ("sdk-version".equals(k)) return "sdk_version";
        if ("country".equals(k) || "locale".equals(k) || "mcc_mnc".equals(k)
                || "fingerprint".equals(k) || "manufacturer".equals(k)
                || "model".equals(k) || "device".equals(k) || "product".equals(k)
                || "hardware".equals(k)) {
            return k;
        }
        return null;
    }

    private static void putFirst(Map<String, String> out, String key, String a, String b) {
        String val = (a != null && !a.trim().isEmpty()) ? a.trim()
                : (b != null && !b.trim().isEmpty()) ? b.trim() : null;
        if (val != null) {
            out.put(key, val);
        }
    }

    private static void putIfPresent(Map<String, String> out, String key, String val) {
        if (val != null && !val.trim().isEmpty()) {
            out.put(key, val.trim());
        }
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.trim().isEmpty() ? "0" : userId.trim();
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16 && i < bytes.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
