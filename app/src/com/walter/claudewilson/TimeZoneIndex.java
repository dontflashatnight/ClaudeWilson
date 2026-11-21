package com.walter.claudewilson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pre-indexed IANA timezone catalog built off the UI thread.
 *
 * <p>Avoids scanning 600+ {@link TimeZone#getAvailableIDs()} synchronously when opening the
 * timezone picker sheet (which caused 45-90ms UI jank on older SoCs).
 */
final class TimeZoneIndex {

    static final class Entry {
        final String id;
        final int offsetMin;
        final String utc;
        final String searchKey;

        Entry(String id, int offsetMin, String utc) {
            this.id = id;
            this.offsetMin = offsetMin;
            this.utc = utc;
            this.searchKey = (id + " " + utc).toLowerCase(Locale.ROOT);
        }
    }

    private static volatile List<Entry> cached;
    private static volatile long cachedAt;
    private static final long TTL_MS = 6L * 3600L * 1000L;

    private TimeZoneIndex() {
    }

    /** Triggers background indexing once so opening the sheet hits warm memory. */
    static void warmAsync() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAt) < TTL_MS) {
            return;
        }
        Thread t = new Thread(() -> build(now), "cw-tz-index");
        t.setDaemon(true);
        t.start();
    }

    static List<Entry> all() {
        long now = System.currentTimeMillis();
        List<Entry> list = cached;
        if (list != null && (now - cachedAt) < TTL_MS) {
            return list;
        }
        return build(now);
    }

    private static synchronized List<Entry> build(long now) {
        List<Entry> existing = cached;
        if (existing != null && (now - cachedAt) < TTL_MS) {
            return existing;
        }
        String[] ids = TimeZone.getAvailableIDs();
        List<Entry> list = new ArrayList<Entry>(ids.length);
        for (String id : ids) {
            if (id == null || id.indexOf('/') < 0) {
                continue;
            }
            if (id.startsWith("Etc/") || id.startsWith("SystemV/")) {
                continue;
            }
            TimeZone tz = TimeZone.getTimeZone(id);
            int offset = tz.getOffset(now) / 60000;
            list.add(new Entry(id, offset, "UTC" + Regions.utcFmt(offset)));
        }
        Collections.sort(list, (a, b) -> {
            int cmp = Integer.compare(a.offsetMin, b.offsetMin);
            return cmp != 0 ? cmp : a.id.compareTo(b.id);
        });
        List<Entry> result = Collections.unmodifiableList(list);
        cached = result;
        cachedAt = now;
        return result;
    }
}
