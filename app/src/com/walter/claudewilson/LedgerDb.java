package com.walter.claudewilson;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.walter.claudewilson.model.DiagnosticArchiveParser;
import com.walter.claudewilson.model.PersonaMatchEngine;
import com.walter.claudewilson.model.Tables;
import com.walter.claudewilson.platform.Diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Private SQLite ledger owned exclusively by ClaudeWilson ({@code databases/ledger.db}).
 *
 * <p>Zero target-directory writes, zero target-database mutations, zero sensitive tokens or chat
 * contents. Stores three tables:
 * <ol>
 *   <li>{@code persona_snapshots}: deduplicated expected personas per (target, space).</li>
 *   <li>{@code upload_observations}: deduplicated historical values passively read from target
 *       telemetry queues and SDK stores, keeping {@code first_seen}, {@code last_seen} and
 *       {@code seen_count}.</li>
 *   <li>{@code bodyguard_incidents}: runtime unknown-signal interception records (Phase 3).</li>
 * </ol>
 */
final class LedgerDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "ledger.db";
    private static final int DB_VERSION = 2;
    private static final long RETENTION_MS = 90L * 24L * 3600L * 1000L;
    private static final int MAX_OBS_PER_SPACE = 2000;
    private static final int MAX_INCIDENTS_PER_SPACE = 500;
    private static final int MAX_HTTP_EVENTS_PER_SPACE = 500;
    private static final int MAX_DIAG_SIGNALS_PER_SPACE = 300;

    private static volatile LedgerDb instance;

    static final class Incident {
        final long id;
        final String targetId;
        final String userId;
        final long occurredAt;
        final String targetVersion;
        final String gateType;
        final String signalKey;
        final String callerFrame;
        final String actionTaken;
        final String resolution;

        Incident(long id, String targetId, String userId, long occurredAt, String targetVersion,
                 String gateType, String signalKey, String callerFrame,
                 String actionTaken, String resolution) {
            this.id = id;
            this.targetId = targetId;
            this.userId = userId;
            this.occurredAt = occurredAt;
            this.targetVersion = targetVersion;
            this.gateType = gateType;
            this.signalKey = signalKey;
            this.callerFrame = callerFrame;
            this.actionTaken = actionTaken;
            this.resolution = resolution;
        }
    }

    static LedgerDb get(Context context) {
        if (instance == null) {
            synchronized (LedgerDb.class) {
                if (instance == null) {
                    instance = new LedgerDb(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LedgerDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS persona_snapshots ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "region_iso TEXT NOT NULL,"
                + "sim_op TEXT NOT NULL,"
                + "sim_iso TEXT NOT NULL,"
                + "sim_carrier TEXT NOT NULL,"
                + "keep_real_sim INTEGER NOT NULL DEFAULT 0,"
                + "net_op TEXT NOT NULL,"
                + "net_iso TEXT NOT NULL,"
                + "tz TEXT NOT NULL,"
                + "utc_offset_sec INTEGER NOT NULL DEFAULT 0,"
                + "has_utc_offset INTEGER NOT NULL DEFAULT 0,"
                + "locale TEXT NOT NULL,"
                + "model TEXT NOT NULL,"
                + "manufacturer TEXT NOT NULL,"
                + "device TEXT NOT NULL,"
                + "product TEXT NOT NULL,"
                + "hardware TEXT NOT NULL,"
                + "fingerprint TEXT NOT NULL,"
                + "os_build TEXT NOT NULL,"
                + "sdk_int TEXT NOT NULL,"
                + "android_id TEXT NOT NULL,"
                + "ipv6_prefix TEXT NOT NULL,"
                + "egress_country TEXT NOT NULL,"
                + "location_city TEXT NOT NULL,"
                + "digest TEXT NOT NULL,"
                + "UNIQUE(target_id, user_id, digest))");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_persona_lookup "
                + "ON persona_snapshots(target_id, user_id, created_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS upload_observations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "field_key TEXT NOT NULL,"
                + "observed_value TEXT NOT NULL,"
                + "source_sdk TEXT NOT NULL,"
                + "first_seen INTEGER NOT NULL,"
                + "last_seen INTEGER NOT NULL,"
                + "seen_count INTEGER NOT NULL DEFAULT 1,"
                + "persona_snapshot_id INTEGER NOT NULL DEFAULT 0,"
                + "is_restricted_leak INTEGER NOT NULL DEFAULT 0,"
                + "UNIQUE(target_id, user_id, field_key, observed_value, source_sdk))");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_lookup "
                + "ON upload_observations(target_id, user_id, last_seen DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS bodyguard_incidents ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "occurred_at INTEGER NOT NULL,"
                + "target_version TEXT NOT NULL,"
                + "gate_type TEXT NOT NULL,"
                + "signal_key TEXT NOT NULL,"
                + "caller_frame TEXT NOT NULL,"
                + "action_taken TEXT NOT NULL,"
                + "resolution TEXT NOT NULL DEFAULT 'OPEN')");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_incidents_lookup "
                + "ON bodyguard_incidents(target_id, user_id, occurred_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS target_http_events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "occurred_at INTEGER NOT NULL,"
                + "method TEXT NOT NULL,"
                + "host TEXT NOT NULL,"
                + "path TEXT NOT NULL,"
                + "status_code INTEGER NOT NULL DEFAULT 0,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "cf_ray TEXT NOT NULL,"
                + "edge_pop TEXT NOT NULL,"
                + "request_id TEXT NOT NULL,"
                + "protocol TEXT NOT NULL,"
                + "tls_diverted TEXT NOT NULL,"
                + "error_message TEXT NOT NULL,"
                + "dedup_key TEXT NOT NULL,"
                + "UNIQUE(target_id, user_id, dedup_key))");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_http_events_lookup "
                + "ON target_http_events(target_id, user_id, occurred_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS target_diag_signals ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "target_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "category TEXT NOT NULL,"
                + "signal_key TEXT NOT NULL,"
                + "signal_value TEXT NOT NULL,"
                + "source_file TEXT NOT NULL,"
                + "first_seen INTEGER NOT NULL,"
                + "last_seen INTEGER NOT NULL,"
                + "seen_count INTEGER NOT NULL DEFAULT 1,"
                + "UNIQUE(target_id, user_id, category, signal_key, signal_value, source_file))");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_diag_signals_lookup "
                + "ON target_diag_signals(target_id, user_id, last_seen DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    /** Inserts or touches a deduplicated persona snapshot and returns its row id. */
    synchronized long recordPersonaSnapshot(PersonaMatchEngine.PersonaSnapshot snap) {
        if (snap == null || snap.targetId.isEmpty() || snap.digest.isEmpty()) {
            return 0L;
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT id FROM persona_snapshots WHERE target_id=? AND user_id=? AND digest=?",
                    new String[]{snap.targetId, snap.userId, snap.digest});
            try {
                if (c.moveToFirst()) {
                    long existingId = c.getLong(0);
                    ContentValues touch = new ContentValues();
                    touch.put("created_at", snap.createdAt);
                    db.update("persona_snapshots", touch, "id=?",
                            new String[]{String.valueOf(existingId)});
                    return existingId;
                }
            } finally {
                c.close();
            }
            ContentValues cv = new ContentValues();
            cv.put("target_id", snap.targetId);
            cv.put("user_id", snap.userId);
            cv.put("created_at", snap.createdAt);
            cv.put("region_iso", snap.regionIso);
            cv.put("sim_op", snap.simOp);
            cv.put("sim_iso", snap.simIso);
            cv.put("sim_carrier", snap.simCarrier);
            cv.put("keep_real_sim", snap.keepRealSim ? 1 : 0);
            cv.put("net_op", snap.netOp);
            cv.put("net_iso", snap.netIso);
            cv.put("tz", snap.tz);
            cv.put("utc_offset_sec", snap.utcOffsetSeconds);
            cv.put("has_utc_offset", snap.hasUtcOffset ? 1 : 0);
            cv.put("locale", snap.locale);
            cv.put("model", snap.model);
            cv.put("manufacturer", snap.manufacturer);
            cv.put("device", snap.device);
            cv.put("product", snap.product);
            cv.put("hardware", snap.hardware);
            cv.put("fingerprint", snap.fingerprint);
            cv.put("os_build", snap.osBuild);
            cv.put("sdk_int", snap.sdkInt);
            cv.put("android_id", snap.androidId);
            cv.put("ipv6_prefix", snap.ipv6Prefix);
            cv.put("egress_country", snap.egressCountry);
            cv.put("location_city", snap.locationCity);
            cv.put("digest", snap.digest);
            return db.insertWithOnConflict("persona_snapshots", null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Diag.warn("ledger", "recordPersonaSnapshot failed", t);
            return 0L;
        }
    }

    /** Returns the most recently active persona snapshot for (targetId, userId), or null. */
    synchronized PersonaMatchEngine.PersonaSnapshot latestPersonaSnapshot(
            String targetId, String userId) {
        if (targetId == null || targetId.isEmpty()) {
            return null;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT id, target_id, user_id, created_at, region_iso, sim_op, sim_iso, "
                            + "sim_carrier, keep_real_sim, net_op, net_iso, tz, utc_offset_sec, "
                            + "has_utc_offset, locale, model, manufacturer, device, product, "
                            + "hardware, fingerprint, os_build, sdk_int, android_id, ipv6_prefix, "
                            + "egress_country, location_city, digest "
                            + "FROM persona_snapshots WHERE target_id=? AND user_id=? "
                            + "ORDER BY created_at DESC LIMIT 1",
                    new String[]{targetId, u});
            try {
                if (!c.moveToFirst()) {
                    return null;
                }
                return new PersonaMatchEngine.PersonaSnapshot(
                        c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                        c.getInt(8) == 1, c.getString(9), c.getString(10), c.getString(11),
                        c.getInt(12), c.getInt(13) == 1, c.getString(14), c.getString(15),
                        c.getString(16), c.getString(17), c.getString(18), c.getString(19),
                        c.getString(20), c.getString(21), c.getString(22), c.getString(23),
                        c.getString(24), c.getString(25), c.getString(26), c.getString(27));
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "latestPersonaSnapshot failed", t);
            return null;
        }
    }

    /** Upserts a batch of observed uploaded values for a target/space with retention pruning. */
    synchronized void recordObservations(String targetId, String userId,
                                         Map<String, String> values, String sourceSdk,
                                         long observedAt, long snapshotId, Tables tables) {
        if (targetId == null || targetId.isEmpty() || values == null || values.isEmpty()) {
            return;
        }
        String u = userId == null ? "0" : userId;
        String sdk = sourceSdk == null ? "unknown" : sourceSdk;
        long ts = observedAt > 0L ? observedAt : System.currentTimeMillis();
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (key == null || key.isEmpty() || val == null || val.trim().isEmpty()) {
                        continue;
                    }
                    String trimmed = val.trim();
                    int leak = PersonaMatchEngine.isRestrictedValue(tables, targetId, key, trimmed)
                            ? 1 : 0;
                    upsertObservationLocked(db, targetId, u, key, trimmed, sdk, ts,
                            snapshotId, leak);
                }
                pruneObservationsLocked(db, targetId, u, System.currentTimeMillis());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "recordObservations failed", t);
        }
    }

    private static void upsertObservationLocked(SQLiteDatabase db, String targetId, String userId,
                                                String key, String value, String sdk, long ts,
                                                long snapshotId, int restrictedLeak) {
        Cursor c = db.rawQuery(
                "SELECT id, first_seen, last_seen, seen_count FROM upload_observations "
                        + "WHERE target_id=? AND user_id=? AND field_key=? "
                        + "AND observed_value=? AND source_sdk=?",
                new String[]{targetId, userId, key, value, sdk});
        try {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                long first = c.getLong(1);
                long last = c.getLong(2);
                int count = c.getInt(3);
                ContentValues upd = new ContentValues();
                upd.put("first_seen", Math.min(first, ts));
                upd.put("last_seen", Math.max(last, ts));
                upd.put("seen_count", count + 1);
                if (snapshotId > 0L) {
                    upd.put("persona_snapshot_id", snapshotId);
                }
                if (restrictedLeak == 1) {
                    upd.put("is_restricted_leak", 1);
                }
                db.update("upload_observations", upd, "id=?", new String[]{String.valueOf(id)});
                return;
            }
        } finally {
            c.close();
        }
        ContentValues ins = new ContentValues();
        ins.put("target_id", targetId);
        ins.put("user_id", userId);
        ins.put("field_key", key);
        ins.put("observed_value", value);
        ins.put("source_sdk", sdk);
        ins.put("first_seen", ts);
        ins.put("last_seen", ts);
        ins.put("seen_count", 1);
        ins.put("persona_snapshot_id", snapshotId);
        ins.put("is_restricted_leak", restrictedLeak);
        db.insert("upload_observations", null, ins);
    }

    private static void pruneObservationsLocked(SQLiteDatabase db, String targetId, String userId,
                                                long now) {
        long cutoff = now - RETENTION_MS;
        db.delete("upload_observations",
                "target_id=? AND user_id=? AND last_seen < ?",
                new String[]{targetId, userId, String.valueOf(cutoff)});
        db.execSQL("DELETE FROM upload_observations WHERE target_id=? AND user_id=? "
                + "AND id NOT IN (SELECT id FROM upload_observations "
                + "WHERE target_id=? AND user_id=? ORDER BY last_seen DESC LIMIT "
                + MAX_OBS_PER_SPACE + ")",
                new Object[]{targetId, userId, targetId, userId});
    }

    /** Loads all persisted observations for (targetId, userId), newest first. */
    synchronized List<PersonaMatchEngine.UploadRecord> loadObservations(
            String targetId, String userId) {
        List<PersonaMatchEngine.UploadRecord> out = new ArrayList<PersonaMatchEngine.UploadRecord>();
        if (targetId == null || targetId.isEmpty()) {
            return out;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT field_key, observed_value, source_sdk, first_seen, last_seen, "
                            + "seen_count, persona_snapshot_id FROM upload_observations "
                            + "WHERE target_id=? AND user_id=? ORDER BY last_seen DESC",
                    new String[]{targetId, u});
            try {
                while (c.moveToNext()) {
                    out.add(new PersonaMatchEngine.UploadRecord(
                            c.getString(0), c.getString(1), c.getString(2),
                            c.getLong(3), c.getLong(4), c.getInt(5), c.getLong(6)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "loadObservations failed", t);
        }
        return out;
    }

    /** Clears observations and snapshots for one (targetId, userId) space. */
    synchronized void clearSpace(String targetId, String userId) {
        if (targetId == null || targetId.isEmpty()) {
            return;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("upload_observations", "target_id=? AND user_id=?",
                    new String[]{targetId, u});
            db.delete("persona_snapshots", "target_id=? AND user_id=?",
                    new String[]{targetId, u});
            db.delete("target_http_events", "target_id=? AND user_id=?",
                    new String[]{targetId, u});
            db.delete("target_diag_signals", "target_id=? AND user_id=?",
                    new String[]{targetId, u});
        } catch (Throwable t) {
            Diag.warn("ledger", "clearSpace failed", t);
        }
    }

    // ---- Bodyguard incident DAO (ready for Phase 2/3) ---------------------------------

    synchronized long recordIncident(String targetId, String userId, long occurredAt,
                                     String targetVersion, String gateType, String signalKey,
                                     String callerFrame, String actionTaken) {
        if (targetId == null || signalKey == null || signalKey.isEmpty()) {
            return 0L;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getWritableDatabase();
            // Deduplicate identical incidents within the same timestamp second.
            Cursor c = db.rawQuery(
                    "SELECT id FROM bodyguard_incidents WHERE target_id=? AND user_id=? "
                            + "AND occurred_at=? AND signal_key=?",
                    new String[]{targetId, u, String.valueOf(occurredAt), signalKey});
            try {
                if (c.moveToFirst()) {
                    return c.getLong(0);
                }
            } finally {
                c.close();
            }
            ContentValues cv = new ContentValues();
            cv.put("target_id", targetId);
            cv.put("user_id", u);
            cv.put("occurred_at", occurredAt);
            cv.put("target_version", targetVersion == null ? "" : targetVersion);
            cv.put("gate_type", gateType == null ? "" : gateType);
            cv.put("signal_key", signalKey);
            cv.put("caller_frame", callerFrame == null ? "" : callerFrame);
            cv.put("action_taken", actionTaken == null ? "" : actionTaken);
            cv.put("resolution", "OPEN");
            long id = db.insert("bodyguard_incidents", null, cv);
            db.execSQL("DELETE FROM bodyguard_incidents WHERE target_id=? AND user_id=? "
                    + "AND id NOT IN (SELECT id FROM bodyguard_incidents "
                    + "WHERE target_id=? AND user_id=? ORDER BY occurred_at DESC LIMIT "
                    + MAX_INCIDENTS_PER_SPACE + ")",
                    new Object[]{targetId, u, targetId, u});
            return id;
        } catch (Throwable t) {
            Diag.warn("ledger", "recordIncident failed", t);
            return 0L;
        }
    }

    synchronized List<Incident> loadIncidents(String targetId, String userId, boolean openOnly) {
        List<Incident> out = new ArrayList<Incident>();
        if (targetId == null || targetId.isEmpty()) {
            return out;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getReadableDatabase();
            String where = openOnly
                    ? "target_id=? AND user_id=? AND resolution='OPEN'"
                    : "target_id=? AND user_id=?";
            Cursor c = db.rawQuery(
                    "SELECT id, target_id, user_id, occurred_at, target_version, gate_type, "
                            + "signal_key, caller_frame, action_taken, resolution "
                            + "FROM bodyguard_incidents WHERE " + where
                            + " ORDER BY occurred_at DESC LIMIT 100",
                    new String[]{targetId, u});
            try {
                while (c.moveToNext()) {
                    out.add(new Incident(c.getLong(0), c.getString(1), c.getString(2),
                            c.getLong(3), c.getString(4), c.getString(5), c.getString(6),
                            c.getString(7), c.getString(8), c.getString(9)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "loadIncidents failed", t);
        }
        return out;
    }

    synchronized void resolveIncident(long id, String resolution) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("resolution", resolution == null ? "IGNORED" : resolution);
            getWritableDatabase().update("bodyguard_incidents", cv, "id=?",
                    new String[]{String.valueOf(id)});
        } catch (Throwable t) {
            Diag.warn("ledger", "resolveIncident failed", t);
        }
    }

    // ---- Structured Target Diagnostic Archive DAO (HTTP + Multi-Dim Staging) ---------

    synchronized void recordHttpEvents(String targetId, String userId,
                                       List<DiagnosticArchiveParser.HttpEvent> events) {
        if (targetId == null || targetId.isEmpty() || events == null || events.isEmpty()) {
            return;
        }
        String u = userId == null ? "0" : userId;
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            for (DiagnosticArchiveParser.HttpEvent e : events) {
                if (e == null || e.dedupKey.isEmpty()) continue;
                ContentValues cv = new ContentValues();
                cv.put("target_id", targetId);
                cv.put("user_id", u);
                cv.put("occurred_at", e.occurredAt);
                cv.put("method", e.method);
                cv.put("host", e.host);
                cv.put("path", e.path);
                cv.put("status_code", e.statusCode);
                cv.put("duration_ms", e.durationMs);
                cv.put("cf_ray", e.cfRay);
                cv.put("edge_pop", e.edgePop);
                cv.put("request_id", e.requestId);
                cv.put("protocol", e.protocol);
                cv.put("tls_diverted", e.tlsDiverted);
                cv.put("error_message", e.errorMessage);
                cv.put("dedup_key", e.dedupKey);
                db.insertWithOnConflict("target_http_events", null, cv,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            db.delete("target_http_events", "target_id=? AND user_id=? AND occurred_at<?",
                    new String[]{targetId, u, String.valueOf(cutoff)});
            db.execSQL("DELETE FROM target_http_events WHERE target_id=? AND user_id=? "
                    + "AND id NOT IN (SELECT id FROM target_http_events "
                    + "WHERE target_id=? AND user_id=? ORDER BY occurred_at DESC LIMIT "
                    + MAX_HTTP_EVENTS_PER_SPACE + ")",
                    new Object[]{targetId, u, targetId, u});
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Diag.warn("ledger", "recordHttpEvents failed", t);
        } finally {
            if (db != null) {
                try { db.endTransaction(); } catch (Throwable ignored) {}
            }
        }
    }

    synchronized List<DiagnosticArchiveParser.HttpEvent> loadHttpEvents(
            String targetId, String userId, int limit) {
        List<DiagnosticArchiveParser.HttpEvent> out =
                new ArrayList<DiagnosticArchiveParser.HttpEvent>();
        if (targetId == null || targetId.isEmpty()) {
            return out;
        }
        String u = userId == null ? "0" : userId;
        int cap = Math.max(1, Math.min(limit, MAX_HTTP_EVENTS_PER_SPACE));
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT occurred_at, method, host, path, status_code, duration_ms, "
                            + "cf_ray, edge_pop, request_id, protocol, tls_diverted, "
                            + "error_message, dedup_key "
                            + "FROM target_http_events WHERE target_id=? AND user_id=? "
                            + "ORDER BY occurred_at DESC LIMIT " + cap,
                    new String[]{targetId, u});
            try {
                while (c.moveToNext()) {
                    out.add(new DiagnosticArchiveParser.HttpEvent(
                            c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                            c.getInt(4), c.getLong(5), c.getString(6), c.getString(7),
                            c.getString(8), c.getString(9), c.getString(10),
                            c.getString(11), c.getString(12)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "loadHttpEvents failed", t);
        }
        return out;
    }

    synchronized void recordDiagSignals(String targetId, String userId,
                                        List<DiagnosticArchiveParser.DimSignal> signals) {
        if (targetId == null || targetId.isEmpty() || signals == null || signals.isEmpty()) {
            return;
        }
        String u = userId == null ? "0" : userId;
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            for (DiagnosticArchiveParser.DimSignal s : signals) {
                if (s == null || s.signalKey.isEmpty() || s.signalValue.isEmpty()) continue;
                Cursor c = db.rawQuery(
                        "SELECT id, seen_count, last_seen FROM target_diag_signals "
                                + "WHERE target_id=? AND user_id=? AND category=? "
                                + "AND signal_key=? AND signal_value=? AND source_file=?",
                        new String[]{targetId, u, s.category, s.signalKey, s.signalValue, s.sourceFile});
                long existId = -1L;
                int prevCount = 0;
                long prevLast = 0L;
                try {
                    if (c.moveToFirst()) {
                        existId = c.getLong(0);
                        prevCount = c.getInt(1);
                        prevLast = c.getLong(2);
                    }
                } finally {
                    c.close();
                }
                if (existId >= 0L) {
                    ContentValues upd = new ContentValues();
                    upd.put("last_seen", Math.max(prevLast, s.lastSeen));
                    upd.put("seen_count", prevCount + 1);
                    db.update("target_diag_signals", upd, "id=?",
                            new String[]{String.valueOf(existId)});
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put("target_id", targetId);
                    cv.put("user_id", u);
                    cv.put("category", s.category);
                    cv.put("signal_key", s.signalKey);
                    cv.put("signal_value", s.signalValue);
                    cv.put("source_file", s.sourceFile);
                    cv.put("first_seen", s.firstSeen);
                    cv.put("last_seen", s.lastSeen);
                    cv.put("seen_count", 1);
                    db.insertWithOnConflict("target_diag_signals", null, cv,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            db.delete("target_diag_signals", "target_id=? AND user_id=? AND last_seen<?",
                    new String[]{targetId, u, String.valueOf(cutoff)});
            db.execSQL("DELETE FROM target_diag_signals WHERE target_id=? AND user_id=? "
                    + "AND id NOT IN (SELECT id FROM target_diag_signals "
                    + "WHERE target_id=? AND user_id=? ORDER BY last_seen DESC LIMIT "
                    + MAX_DIAG_SIGNALS_PER_SPACE + ")",
                    new Object[]{targetId, u, targetId, u});
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            Diag.warn("ledger", "recordDiagSignals failed", t);
        } finally {
            if (db != null) {
                try { db.endTransaction(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Returns the latest structured value per (category, signal_key), newest-first. */
    synchronized List<DiagnosticArchiveParser.DimSignal> loadLatestDiagSignals(
            String targetId, String userId) {
        List<DiagnosticArchiveParser.DimSignal> out =
                new ArrayList<DiagnosticArchiveParser.DimSignal>();
        if (targetId == null || targetId.isEmpty()) {
            return out;
        }
        String u = userId == null ? "0" : userId;
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT category, signal_key, signal_value, source_file, "
                            + "first_seen, last_seen, seen_count "
                            + "FROM target_diag_signals WHERE target_id=? AND user_id=? "
                            + "ORDER BY last_seen DESC LIMIT 200",
                    new String[]{targetId, u});
            java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
            try {
                while (c.moveToNext()) {
                    String cat = c.getString(0);
                    String key = c.getString(1);
                    String id = cat + "|" + key;
                    if (!seen.add(id)) continue;
                    out.add(new DiagnosticArchiveParser.DimSignal(
                            cat, key, c.getString(2), c.getString(3),
                            c.getLong(4), c.getLong(5), c.getInt(6)));
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Diag.warn("ledger", "loadLatestDiagSignals failed", t);
        }
        return out;
    }
}
