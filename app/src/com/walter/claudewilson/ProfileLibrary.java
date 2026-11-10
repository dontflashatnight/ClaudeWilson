package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.BleProfile;
import com.walter.claudewilson.model.WifiProfile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Region profile library: Wi-Fi SSID patterns, BLE device names and sensor class ranges.
 * Assets are read once in the control app; the target only receives the resolved values.
 */
final class ProfileLibrary {
    private static final Map<String, List<WifiProfile.Template>> WIFI = new HashMap<>();
    private static final Map<String, List<BleProfile.Template>> BLE = new HashMap<>();
    private static List<String[]> sensors;

    private ProfileLibrary() {
    }

    static synchronized List<WifiProfile.Template> wifi(Context context, String iso) {
        String key = iso == null ? "" : iso.toLowerCase(Locale.ROOT);
        List<WifiProfile.Template> cached = WIFI.get(key);
        if (cached != null) {
            return cached;
        }
        List<WifiProfile.Template> out = new ArrayList<>();
        for (String[] row : rows(context, "catalog/wifi.txt")) {
            if (row.length < 5 || !matches(row[0], key)) {
                continue;
            }
            try {
                out.add(new WifiProfile.Template(row[1], row[2],
                        Double.parseDouble(row[3]), Integer.parseInt(row[4])));
            } catch (NumberFormatException ignored) {
            }
        }
        WIFI.put(key, out);
        return out;
    }

    static synchronized List<BleProfile.Template> ble(Context context, String iso) {
        String key = iso == null ? "" : iso.toLowerCase(Locale.ROOT);
        List<BleProfile.Template> cached = BLE.get(key);
        if (cached != null) {
            return cached;
        }
        List<BleProfile.Template> out = new ArrayList<>();
        for (String[] row : rows(context, "catalog/ble.txt")) {
            if (row.length < 4 || !matches(row[0], key)) {
                continue;
            }
            try {
                out.add(new BleProfile.Template(row[1], Integer.parseInt(row[2]),
                        Integer.parseInt(row[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        BLE.put(key, out);
        return out;
    }

    static synchronized String[] sensor(Context context, long seed) {
        if (sensors == null) {
            sensors = rows(context, "catalog/sensors.txt");
        }
        if (sensors.isEmpty()) {
            return null;
        }
        return sensors.get((int) Math.floorMod(seed, sensors.size()));
    }

    private static boolean matches(String rowIso, String key) {
        return "*".equals(rowIso) || rowIso.equalsIgnoreCase(key);
    }

    private static List<String[]> rows(Context context, String asset) {
        List<String[]> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(asset), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String row = line.trim();
                if (row.isEmpty() || row.startsWith("#")) {
                    continue;
                }
                out.add(row.split("\\|", -1));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
