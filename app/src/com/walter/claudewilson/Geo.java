package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.GeoTable;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Loads the region coordinate table once; the control app resolves the point before publishing. */
final class Geo {
    private static volatile GeoTable table;

    private Geo() {
    }

    static GeoTable.Point point(Context context, String iso, String zone) {
        if (context == null || iso == null) {
            return null;
        }
        GeoTable current = table;
        if (current == null) {
            synchronized (Geo.class) {
                current = table;
                if (current == null) {
                    current = load(context);
                    table = current;
                }
            }
        }
        return current.point(iso, zone);
    }

    private static GeoTable load(Context context) {
        StringBuilder text = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("catalog/geo.txt"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (Throwable t) {
            android.util.Log.w("ClaudeWilson", "geo: cannot load catalog", t);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
        }
        return GeoTable.parse(text.toString());
    }
}
