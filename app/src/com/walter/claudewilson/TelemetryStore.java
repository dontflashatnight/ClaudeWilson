package com.walter.claudewilson;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only observation store for values that disappear from the target's own queue.
 *
 * <p>datatransport deletes an event after a successful upload, so reading only the live queue
 * cannot answer "what did the target last report". This store keeps the latest value plus first
 * and last seen timestamps. The target-process journal is ingested through {@link #ingestJournal}.
 */
final class TelemetryStore {
    private static final String DIR = "telemetry";
    private static final String VERSION = "1";
    private static final java.util.Set<String> TRANSPORT_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("country", "locale", "mcc_mnc", "tz-offset",
                    "fingerprint", "net-type", "mobile-subtype", "os-build",
                    "manufacturer", "model", "device", "product", "hardware",
                    "sdk-version", "application_build"));
    private static final java.util.Set<String> HOOK_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("engine", "target", "target_pkg", "armed", "selfcheck",
                    "pid", "telemetry", "location", "location_served", "location_last",
                    "wifi", "wifi_scan", "wifi_connection", "ble", "ble_scan", "ble_bonded",
                    "sensors", "sensor_events", "webview"));

    static final class Entry {
        final String value;
        final long firstSeen;
        final long lastSeen;
        final int count;
        final String source;

        Entry(String value, long firstSeen, long lastSeen, int count, String source) {
            this.value = value;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.count = count;
            this.source = source;
        }
    }

    private TelemetryStore() {
    }

    /**
     * Reliable P0 path: copy the target database and its WAL into the control app's cache with
     * root, then query it read-only. This does not depend on {@code strings} output or on a hook
     * being installed, and it still sees a row before the target uploads and deletes it.
     */
    static synchronized void ingestDatabase(Context context, String target, String pkg) {
        if (context == null || target == null || pkg == null
                || !com.walter.claudewilson.model.ShellQuote.packageName(pkg)) {
            return;
        }
        File dir = new File(context.getCacheDir(), "cw-db");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File db = new File(dir, "transport.db");
        File wal = new File(dir, "transport.db-wal");
        File shm = new File(dir, "transport.db-shm");
        String base = "/data/data/" + pkg + "/databases/com.google.android.datatransport.events";
        String cmd = "cp -f " + base + " " + db.getAbsolutePath()
                + " 2>/dev/null; cp -f " + base + "-wal " + wal.getAbsolutePath()
                + " 2>/dev/null; cp -f " + base + "-shm " + shm.getAbsolutePath()
                + " 2>/dev/null; chmod 644 " + db.getAbsolutePath() + " 2>/dev/null; true";
        String out = RootShell.run(cmd, 1024);
        if (!db.isFile()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        SQLiteDatabase database = null;
        Cursor cursor = null;
        try {
            database = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            cursor = database.rawQuery(
                    "SELECT m.name, m.value FROM event_metadata m "
                            + "JOIN events e ON e._id = m.event_id "
                            + "WHERE e._id = (SELECT MAX(_id) FROM events)", null);
            while (cursor.moveToNext()) {
                String name = cursor.isNull(0) ? null : cursor.getString(0);
                String value = cursor.isNull(1) ? null : cursor.getString(1);
                if (name != null && value != null && allowed(normalize(name))) {
                    fields.put(normalize(name), value);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (database != null) {
                database.close();
            }
        }
        // SQLite may fail on a copied WAL or a vendor build; the text rows are still enough
        // for the allowlisted metadata and avoid treating a query failure as "no event".
        if (fields.isEmpty()) {
            fields.putAll(printableFields(db));
        }
        db.delete();
        wal.delete();
        shm.delete();
        ingest(context, target, fields, "target-db");
    }

    private static Map<String, String> printableFields(File file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            StringBuilder run = new StringBuilder();
            for (byte b : bytes) {
                int value = b & 0xFF;
                if (value >= 0x20 && value < 0x7F) {
                    run.append((char) value);
                } else {
                    parseRun(run.toString(), out);
                    run.setLength(0);
                }
            }
            parseRun(run.toString(), out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void parseRun(String line, Map<String, String> out) {
        if (line.length() < 6) {
            return;
        }
        String[] keys = {"country", "locale", "mcc_mnc", "tz-offset", "fingerprint",
                "net-type", "mobile-subtype", "os-build", "os-uild", "manufacturer",
                "model", "device", "product", "hardware", "sdk-version", "application_build"};
        for (String key : keys) {
            if (line.startsWith(key) && line.length() > key.length()) {
                String canonical = "os-uild".equals(key) ? "os-build" : key;
                String value = line.substring(key.length()).trim();
                if (!value.isEmpty() && allowed(canonical)) {
                    out.put(canonical, value);
                }
                return;
            }
        }
    }

    static synchronized Map<String, Entry> read(Context context, String target) {        Map<String, Entry> out = new LinkedHashMap<>();
        File file = file(context, target);
        if (!file.isFile()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(readText(file));
            JSONObject fields = root.optJSONObject("fields");
            if (fields == null) {
                return out;
            }
            java.util.Iterator<String> it = fields.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!allowed(key)) {
                    continue;
                }
                JSONObject value = fields.optJSONObject(key);
                if (value == null) {
                    continue;
                }
                out.put(key, new Entry(value.optString("v", ""),
                        value.optLong("first", 0L), value.optLong("last", 0L),
                        value.optInt("count", 1), value.optString("source", "")));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static synchronized void ingest(Context context, String target,
            Map<String, String> fields, String source) {
        if (context == null || target == null || fields == null || fields.isEmpty()) {
            return;
        }
        Map<String, Entry> current = read(context, target);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String key = normalize(field.getKey());
            String value = field.getValue();
            if (key == null || value == null || value.isEmpty() || !allowed(key)) {
                continue;
            }
            Entry old = current.get(key);
            if (old != null && old.value.equals(value)) {
                current.put(key, new Entry(value, old.firstSeen, now, old.count + 1, source));
            } else {
                current.put(key, new Entry(value, now, now, 1, source));
                appendHistory(context, target, key, value, source, now);
            }
        }
        write(context, target, current);
    }

    /** Parse the target-process JSONL journal written by the hook. */
    static synchronized void ingestJournal(Context context, String target, String text) {
        if (context == null || target == null || text == null || text.isEmpty()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\\n")) {
            String row = line.trim();
            if (row.isEmpty()) {
                continue;
            }
            try {
                JSONObject object = new JSONObject(row);
                if (!"meta".equals(object.optString("type"))) {
                    continue;
                }
                String name = normalize(object.optString("name", ""));
                String value = object.optString("value", "");
                if (name != null && !value.isEmpty()) {
                    fields.put(name, value);
                }
            } catch (Throwable ignored) {
            }
        }
        ingest(context, target, fields, "hook-journal");
    }

    static String latest(Map<String, Entry> entries, String key) {
        Entry entry = entries.get(normalize(key));
        return entry == null ? null : entry.value;
    }

    static long lastSeen(Map<String, Entry> entries, String key) {
        Entry entry = entries.get(normalize(key));
        return entry == null ? 0L : entry.lastSeen;
    }

    private static String normalize(String key) {
        if (key == null) {
            return null;
        }
        String value = key.trim();
        if ("os-uild".equals(value)) {
            return "os-build";
        }
        return value;
    }

    private static boolean allowed(String key) {
        if (TRANSPORT_KEYS.contains(key) || key.startsWith("cookie.")) {
            return true;
        }
        if (key.startsWith("hook.")) {
            return HOOK_KEYS.contains(key.substring("hook.".length()));
        }
        return false;
    }

    private static void write(Context context, String target, Map<String, Entry> entries) {
        try {
            File file = file(context, target);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            JSONObject fields = new JSONObject();
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                JSONObject value = new JSONObject();
                value.put("v", entry.value);
                value.put("first", entry.firstSeen);
                value.put("last", entry.lastSeen);
                value.put("count", entry.count);
                value.put("source", entry.source);
                fields.put(item.getKey(), value);
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("fields", fields);
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                Files.copy(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                temp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendHistory(Context context, String target, String key,
            String value, String source, long now) {
        try {
            File file = new File(dir(context, target), "history.jsonl");
            JSONObject row = new JSONObject();
            row.put("t", now);
            row.put("k", key);
            row.put("v", value);
            row.put("s", source);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((row + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static File dir(Context context, String target) {
        return new File(new File(context.getFilesDir(), DIR), safe(target));
    }

    private static File file(Context context, String target) {
        return new File(dir(context, target), "latest.json");
    }

    private static String safe(String target) {
        return target.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
