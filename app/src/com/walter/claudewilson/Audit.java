package com.walter.claudewilson;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Append-only record of what the app changed.
 *
 * <p>An apply touches the timezone, two app locale settings, a carrier override and possibly the
 * per-user SSAID table. Without a record, "what did the last run change" could only be guessed
 * after a restart -- and the backups are keyed by user, which makes reconstruction harder still.
 *
 * <p>Bounded: the file is dropped when it grows past {@link #MAX_BYTES}, so it cannot fill storage.
 */
final class Audit {
    private static final String FILE = "apply-audit.log";
    private static final int MAX_BYTES = 64 * 1024;

    private Audit() {
    }

    static void record(Context context, String what, String detail) {
        try {
            File file = new File(context.getFilesDir(), FILE);
            if (file.length() > MAX_BYTES) {
                file.delete();
            }
            String line = stamp() + "  " + what + "  " + detail + "\n";
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // auditing must never break the action it describes
        }
    }

    /**
     * UTC, marked with {@code Z}: an apply changes the device timezone, so a local stamp makes
     * consecutive lines jump by the applied offset (18:35 JST written after 17:35 CST) and the
     * log reads out of order exactly when it matters.
     */
    private static String stamp() {
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /** The last {@code lines} entries, oldest first; empty when nothing was recorded. */
    static String tail(Context context, int lines) {
        List<String> all = read(context);
        int from = Math.max(0, all.size() - Math.max(1, lines));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < all.size(); i++) {
            out.append(all.get(i)).append('\n');
        }
        return out.toString().trim();
    }

    /** Entries whose category matches {@code what}, e.g. {@code su} for channel actions. */
    static String tail(Context context, String what, int lines) {
        List<String> matched = new ArrayList<String>();
        String marker = "  " + what + "  ";
        for (String line : read(context)) {
            if (line.contains(marker)) {
                matched.add(line);
            }
        }
        int from = Math.max(0, matched.size() - Math.max(1, lines));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < matched.size(); i++) {
            out.append(matched.get(i)).append('\n');
        }
        return out.toString().trim();
    }

    private static List<String> read(Context context) {
        BufferedReader reader = null;
        try {
            File file = new File(context.getFilesDir(), FILE);
            if (!file.isFile()) {
                return new ArrayList<String>();
            }
            List<String> all = new ArrayList<String>();
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                all.add(line);
            }
            return all;
        } catch (Exception e) {
            return new ArrayList<String>();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // read-only stream
                }
            }
        }
    }
}
