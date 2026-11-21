package com.walter.claudewilson.hook;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Captures datatransport rows before the library uploads and deletes them.
 *
 * <p>This is a SQLite table hook rather than a datatransport class hook: the table names are more
 * stable across target versions. Only allowlisted metadata is written; the payload is reduced to
 * size and SHA-256. The target's database is never modified.
 */
final class TelemetryHooks {
    static volatile String status = "not-run";
    private static final int QUEUE_CAPACITY = 512;
    private static final String[] ALLOWED = {
            "country", "locale", "mcc_mnc", "tz-offset", "fingerprint", "net-type",
            "mobile-subtype", "os-build", "os-uild", "manufacturer", "model", "device",
            "product", "hardware", "sdk-version", "application_build"
    };
    private static final BlockingQueue<String> QUEUE =
            new ArrayBlockingQueue<String>(QUEUE_CAPACITY);
    private static final int MAX_UNSEEN_KEYS = 128;
    private static final java.util.Set<String> UNSEEN_KEYS = new java.util.LinkedHashSet<String>();
    private static volatile boolean started;
    private static volatile File journal;
    private static volatile ClassLoader classLoader;

    private TelemetryHooks() {
    }

    static void install(ClassLoader loader) {
        classLoader = loader;
        try {
            Class<?> database = XposedHelpers.findClass(
                    "android.database.sqlite.SQLiteDatabase", loader);
            XposedBridge.hookAllMethods(database, "insert", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    capture(param.args);
                }
            });
            XposedBridge.hookAllMethods(database, "insertOrThrow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    capture(param.args);
                }
            });
            XposedBridge.hookAllMethods(database, "insertWithOnConflict", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    capture(param.args);
                }
            });
            status = "ok";
            XposedBridge.log("ClaudeWilsonHook telemetry status=ok");
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
            XposedBridge.log("ClaudeWilsonHook telemetry: " + t);
        }
    }

    private static void capture(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        String table = null;
        ContentValues values = null;
        for (Object arg : args) {
            if (arg instanceof String) {
                table = (String) arg;
            } else if (arg instanceof ContentValues) {
                values = (ContentValues) arg;
            }
        }
        if (table == null || values == null
                || (!"events".equals(table) && !"event_metadata".equals(table))) {
            return;
        }
        Context context = HookContext.app(classLoader);
        if (context == null) {
            return;
        }
        try {
            JSONObject row = new JSONObject();
            if ("event_metadata".equals(table)) {
                String name = values.getAsString("name");
                String value = values.getAsString("value");
                if (name == null) {
                    return;
                }
                if (!allowed(name)) {
                    recordUnseenKey(context, name);
                    return;
                }
                if (value == null) {
                    return;
                }
                row.put("type", "meta");
                row.put("name", "os-uild".equals(name) ? "os-build" : name);
                row.put("value", value);
                Long eventId = values.getAsLong("event_id");
                if (eventId != null) {
                    row.put("event", eventId);
                }
            } else {
                row.put("type", "event");
                row.put("transport", values.getAsString("transport_name"));
                Long timestamp = values.getAsLong("timestamp_ms");
                if (timestamp != null) {
                    row.put("ts", timestamp);
                }
                byte[] payload = values.getAsByteArray("payload");
                if (payload != null) {
                    row.put("bytes", payload.length);
                    row.put("hash", sha256(payload));
                }
                Long attempts = values.getAsLong("num_attempts");
                if (attempts != null) {
                    row.put("attempts", attempts);
                }
                Long code = values.getAsLong("code");
                if (code != null) {
                    row.put("code", code);
                }
            }
            row.put("captured", System.currentTimeMillis());
            enqueue(context, row.toString());
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook telemetry row: " + t);
        }
    }

    private static boolean allowed(String name) {
        for (String key : ALLOWED) {
            if (key.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records only the key name (never its value) for allowlist misses so target-watch can detect
     * newly added Firelog metadata keys after a target update without storing PII.
     */
    private static void recordUnseenKey(Context context, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!validKeyName(name)) {
            return;
        }
        boolean added;
        synchronized (UNSEEN_KEYS) {
            if (UNSEEN_KEYS.size() >= MAX_UNSEEN_KEYS || UNSEEN_KEYS.contains(name)) {
                return;
            }
            added = UNSEEN_KEYS.add(name);
        }
        if (!added) {
            return;
        }
        try {
            JSONObject row = new JSONObject();
            row.put("type", "unseen_key");
            row.put("name", name);
            row.put("captured", System.currentTimeMillis());
            enqueue(context, row.toString());
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook telemetry unseen_key: " + t);
        }
    }

    private static boolean validKeyName(String name) {
        if (name.isEmpty() || name.length() > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ':' || c == '/' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static void enqueue(Context context, String line) {
        synchronized (TelemetryHooks.class) {
            if (!started) {
                journal = new File(new File(context.getCacheDir(), "cw-telemetry"),
                        "events.jsonl");
                File dir = journal.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
                started = true;
                Thread writer = new Thread(TelemetryHooks::drain, "Binder:bg");
                writer.setDaemon(true);
                writer.start();
            }
        }
        if (!QUEUE.offer(line)) {
            XposedBridge.log("ClaudeWilsonHook telemetry queue full");
        }
    }

    private static void drain() {
        while (true) {
            try {
                String line = QUEUE.take();
                File file = journal;
                if (file == null) {
                    continue;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook telemetry write: " + t);
            }
        }
    }

    private static String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b & 0xFF));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
