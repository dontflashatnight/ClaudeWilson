package com.walter.claudewilson;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unprivileged local reads. No root, root escalation or network requests.
 *
 * <p>The property area is read once per refresh and cached. Rendering used to spawn one
 * {@code getprop} process per key (11–14 per full screen, ~16ms each on the test device), so a
 * single region switch could spend close to a second just forking {@code getprop}. Callers on the
 * UI thread now read this map; only {@link #refresh()} (workers, or the one-time startup warm-up)
 * touches the property area.
 */
final class DeviceSignals {
    private static final long REFRESH_COALESCE_NS = 200_000_000L;   // 200ms

    private static volatile Map<String, String> props = Collections.emptyMap();
    private static volatile long refreshedAt;
    private static final Object LOCK = new Object();

    private DeviceSignals() {}

    /**
     * One-time startup warm-up. A single {@code getprop} dump is ~40ms; doing it once means the
     * first screen does not fall back to a per-key process for every row.
     */
    static void warm() {
        refresh();
    }

    /** Re-reads the whole property area. Safe to call from any worker thread. */
    static void refresh() {
        refresh(false);
    }

    /** Bypasses the 200 ms coalescing window when a post-write read must be current. */
    static void refreshNow() {
        refresh(true);
    }

    private static void refresh(boolean force) {
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (!force && now - refreshedAt < REFRESH_COALESCE_NS && !props.isEmpty()) {
                return;
            }
            Map<String, String> parsed = parse(execAll("getprop"));
            if (!parsed.isEmpty()) {
                props = parsed;
            }
            refreshedAt = now;
        }
    }

    /** Cached property value, or null when absent/unreadable. */
    static String getprop(String key) {
        Map<String, String> snapshot = props;
        String value = snapshot.get(key);
        if (value != null) {
            return value.isEmpty() ? null : value.trim();
        }
        if (!snapshot.isEmpty()) {
            // The dump is authoritative: a missing key is absent, not "try a process per row".
            return null;
        }
        return single(key);
    }

    static String execOut(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            r.close();
            return s == null || s.isEmpty() ? null : s.trim();
        } catch (Throwable t) {
            return null;
        } finally { if (p != null) p.destroy(); }
    }

    static String readFile(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) { return null; }
    }

    /** One {@code getprop} process for the whole property area. */
    private static String execAll(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                return null;
            }
            r.close();
            return p.exitValue() == 0 ? out.toString() : null;
        } catch (Throwable t) {
            return null;
        } finally { if (p != null) p.destroy(); }
    }

    /** A single-key read for the window before the first dump has landed. */
    private static String single(String key) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            r.close();
            return s == null || s.isEmpty() ? null : s.trim();
        } catch (Throwable t) {
            return null;
        } finally { if (p != null) p.destroy(); }
    }

    /** Parses the {@code [key]: [value]} lines printed by {@code getprop}. */
    private static Map<String, String> parse(String dump) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (dump == null) {
            return values;
        }
        for (String line : dump.split("\n")) {
            int separator = line.indexOf("]: [");
            if (!line.startsWith("[") || separator < 0) {
                continue;
            }
            int end = line.endsWith("]") ? line.length() - 1 : line.length();
            if (separator + 4 > end) {
                continue;
            }
            values.put(line.substring(1, separator), line.substring(separator + 4, end));
        }
        return values;
    }
}
