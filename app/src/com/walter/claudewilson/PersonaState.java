package com.walter.claudewilson;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persona state, keyed by Android user, kept in a plain file.
 *
 * <p>The android id is a per-user value: each user (persona space) has its own SSAID row in
 * {@code settings_ssaid.xml}. Keeping one global record meant switching users still showed — and
 * re-injected — the previous user's id.
 *
 * <p>The file store is deliberate. The app's preference area has been observed to stop accepting
 * writes on a boot where the framework was restarted; the audit log and the region salt already
 * use the app-files path, and this state must survive with them.
 */
final class PersonaState {
    private static final String AID = "root_aid.";
    private static final String DEVICE = "root_dev.";
    private static final String CUSTOM_DEVICE = "root_custom.";
    private static final String DEVICE_OVERRIDE = "ovr_dev.";
    private static final String AID_OVERRIDE = "ovr_aid.";
    private static final String SEED = "root_seed.";
    private static final String HISTORY = "root_hist.";
    private static final String CARRIER = "root_carrier.";
    private static final String OP = "root_op.";
    private static final String CSC = "root_csc.";
    private static final String CC_HINT = "root_cc_hint.";
    private static final String LOCATION = "root_loc.";
    private static final String LOC_LAT = "root_loc_lat.";
    private static final String LOC_LNG = "root_loc_lng.";
    private static final String LOC_CITY = "root_loc_city.";
    private static final String PROFILE_SALT = "root_psalt.";
    private static final String UNLISTED_ID = "root_unlisted.";
    private static final String INJECT_SIG = "inject_sig.";
    private static final String USERS = "root_users";
    private static final String LEGACY_AID = "root_aid";
    private static final String LEGACY_DEVICE = "root_dev";
    private static final String LEGACY_SEED = "root_seed";
    private static final String FILE = "persona-state.json";

    private final Store store;

    PersonaState(Context context) {
        store = new Store(new File(context.getApplicationContext().getFilesDir(), FILE));
        migrateLegacy();
    }

    /** Before the per-user keys existed the values were global; they belong to user 0. */
    private void migrateLegacy() {
        if (!store.contains(LEGACY_AID) && !store.contains(LEGACY_DEVICE)) {
            return;
        }
        if (!store.contains(DEVICE + "0")) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put(AID + "0", store.get(LEGACY_AID, ""));
            values.put(DEVICE + "0", store.get(LEGACY_DEVICE, "0"));
            values.put(SEED + "0", store.get(LEGACY_SEED, "0"));
            values.put(USERS, withUser(users(), "0"));
            store.putAll(values);
        }
        store.remove(LEGACY_AID);
        store.remove(LEGACY_DEVICE);
        store.remove(LEGACY_SEED);
    }

    String aid(String user) {
        return store.get(AID + user, null);
    }

    int device(String user) {
        return store.getInt(DEVICE + user, 0);
    }

    long seed(String user) {
        return store.getLong(SEED + user, 0L);
    }

    /** Per-space SIM carrier override, or null when the region profile should be used. */
    String carrier(String user) {
        return store.get(CARRIER + user, null);
    }

    void putCarrier(String user, String carrier) {
        store.putString(CARRIER + user, carrier);
    }

    /** Drop the override: the selected region's carrier profile wins again. */
    void clearCarrier(String user) {
        store.remove(CARRIER + user);
    }

    /** Per-space SIM operator (mccmnc) override, or null when the region profile should be used. */
    String op(String user) {
        return store.get(OP + user, null);
    }

    void putOp(String user, String op) {
        store.putString(OP + user, op);
    }

    /** Drop the override: the selected region's operator profile wins again. */
    void clearOp(String user) {
        store.remove(OP + user);
    }

    /** Target product name the user asked the CSC row to write, or null when unassigned. */
    String csc(String user) {
        return store.get(CSC + user, null);
    }

    void putCsc(String user, String productName) {
        store.putString(CSC + user, productName);
    }

    /** Forget the target: the row shows the device's own tag again. */
    void clearCsc(String user) {
        store.remove(CSC + user);
    }

    /** Target-visible country_code_hint override, or null when the region ISO should be used. */
    String countryHint(String user) {
        return store.get(CC_HINT + user, null);
    }

    void putCountryHint(String user, String iso) {
        store.putString(CC_HINT + user, iso);
    }

    void clearCountryHint(String user) {
        store.remove(CC_HINT + user);
    }

    /** Synthetic location defaults on: a region persona without a place is only half an identity. */
    boolean location(String user) {
        return store.getBoolean(LOCATION + user, true);
    }

    void putLocation(String user, boolean enabled) {
        store.putBoolean(LOCATION + user, enabled);
    }

    /** A map-picked point for this space; absent means "follow the region centre". */
    boolean hasLocationPoint(String user) {
        return store.contains(LOC_LAT + user) && store.contains(LOC_LNG + user);
    }

    double locationLatitude(String user) {
        return Double.longBitsToDouble(store.getLong(LOC_LAT + user, 0L));
    }

    double locationLongitude(String user) {
        return Double.longBitsToDouble(store.getLong(LOC_LNG + user, 0L));
    }

    String locationCity(String user) {
        return store.get(LOC_CITY + user, "");
    }

    void putLocationPoint(String user, double latitude, double longitude, String city) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(LOC_LAT + user, String.valueOf(Double.doubleToRawLongBits(latitude)));
        values.put(LOC_LNG + user, String.valueOf(Double.doubleToRawLongBits(longitude)));
        values.put(LOC_CITY + user, city == null ? "" : city);
        store.putAll(values);
    }

    void clearLocationPoint(String user) {
        store.remove(LOC_LAT + user);
        store.remove(LOC_LNG + user);
        store.remove(LOC_CITY + user);
    }

    /**
     * Salt for one (space, model identity) pair. Keyed by the model fields, not by the picker
     * index, so an unlisted/custom model cannot fall back to the running device's radio profile.
     */
    long profileSalt(String user, String identity) {
        String key = PROFILE_SALT + user + "."
                + com.walter.claudewilson.model.ProfileIdentity.key(identity);
        long value = store.getLong(key, 0L);
        if (value == 0L) {
            value = new java.security.SecureRandom().nextLong();
            if (value == 0L) {
                value = 0x9E3779B97F4A7C15L;
            }
            store.putString(key, String.valueOf(value));
        }
        return value;
    }

    /**
     * A stable random identity for a model that has no catalog row and no usable identity
     * fields. It is not a spoofed model name; it only gives the radio layer something to key on,
     * so an unlisted model still gets its own BSSID/BLE values instead of sharing index 0.
     */
    String unlistedIdentity(String user) {
        String key = UNLISTED_ID + user;
        String value = store.get(key, null);
        if (value == null || value.trim().isEmpty()) {
            value = "unlisted:" + java.util.UUID.randomUUID();
            store.putString(key, value);
        }
        return value;
    }

    /** Drop the AID override: the row falls back to the device's own SSAID. */
    void clearAid(String user) {
        store.remove(AID + user);
        store.remove(AID_OVERRIDE + user);
    }

    /*
     * Overrides are opt-in. A stored value alone does not mean the user asked for a target: earlier
     * builds wrote one whenever a row was touched, and those leftovers kept reappearing as an
     * "expected" value the user never chose. The flag records a deliberate choice.
     */

    boolean hasDeviceOverride(String user) {
        return store.getBoolean(DEVICE_OVERRIDE + user, false);
    }

    void markDeviceOverride(String user) {
        store.putBoolean(DEVICE_OVERRIDE + user, true);
    }

    void clearDeviceOverride(String user) {
        store.remove(DEVICE_OVERRIDE + user);
        store.remove(CUSTOM_DEVICE + user);
    }

    /** Custom 14-field device row for this space, or null when the catalog/self row is used. */
    String[] customDevice(String user) {
        String raw = store.get(CUSTOM_DEVICE + user, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\u001F", -1);
        return parts.length == 14 ? parts : null;
    }

    void putCustom(String user, String[] device, String aid, long seed) {
        if (device == null || device.length != 14) {
            return;
        }
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < device.length; i++) {
            if (i > 0) {
                raw.append('\u001F');
            }
            raw.append(device[i] == null ? "" : device[i]);
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(DEVICE + user, "0");
        values.put(CUSTOM_DEVICE + user, raw.toString());
        values.put(AID + user, aid);
        values.put(SEED + user, String.valueOf(seed));
        values.put(DEVICE_OVERRIDE + user, "true");
        values.put(USERS, withUser(users(), user));
        store.putAll(values);
    }

    boolean hasAidOverride(String user) {
        return store.getBoolean(AID_OVERRIDE + user, false);
    }

    void markAidOverride(String user) {
        store.putBoolean(AID_OVERRIDE + user, true);
    }

    void clearAidOverride(String user) {
        store.remove(AID_OVERRIDE + user);
    }

    /** Recent persona picks for this space, oldest first, comma-separated. */
    String history(String user) {
        return store.get(HISTORY + user, "");
    }

    /**
     * Signature of the payload the target process was last injected with, or null when nothing
     * was injected for this space.
     */
    String injectedSig(String user) {
        return store.get(INJECT_SIG + user, null);
    }

    void markInjected(String user, String sig) {
        if (sig != null) {
            store.putString(INJECT_SIG + user, sig);
        }
    }

    void clearInjected(String user) {
        store.remove(INJECT_SIG + user);
    }

    void putHistory(String user, String history) {
        store.putString(HISTORY + user, history);
    }

    void put(String user, int device, String aid, long seed) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(DEVICE + user, String.valueOf(device));
        values.put(AID + user, aid);
        values.put(SEED + user, String.valueOf(seed));
        values.put(USERS, withUser(users(), user));
        store.putAll(values);
        store.remove(CUSTOM_DEVICE + user);
    }

    /** Users this app has written persona state for, in the order they were first touched. */
    List<String> users() {
        List<String> out = new ArrayList<String>();
        String raw = store.get(USERS, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String user = part.trim();
            if (!user.isEmpty() && !out.contains(user)) {
                out.add(user);
            }
        }
        return out;
    }

    String usersText() {
        List<String> users = users();
        return users.isEmpty() ? "-" : TextUtils.join(", ", users);
    }

    private static String withUser(List<String> users, String user) {
        if (users.contains(user)) {
            return TextUtils.join(",", users);
        }
        List<String> copy = new ArrayList<String>(users);
        copy.add(user);
        return TextUtils.join(",", copy);
    }

    /** Small JSON-backed key/value store; one file, one process, synchronous writes. */
    private static final class Store {
        private final File file;
        private final Map<String, String> data = new LinkedHashMap<String, String>();

        Store(File file) {
            this.file = file;
            load();
        }

        synchronized String get(String key, String fallback) {
            String value = data.get(key);
            return value == null ? fallback : value;
        }

        synchronized int getInt(String key, int fallback) {
            try {
                return Integer.parseInt(get(key, String.valueOf(fallback)));
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        synchronized long getLong(String key, long fallback) {
            try {
                return Long.parseLong(get(key, String.valueOf(fallback)));
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        synchronized boolean getBoolean(String key, boolean fallback) {
            return "true".equalsIgnoreCase(get(key, String.valueOf(fallback)));
        }

        synchronized boolean contains(String key) {
            return data.containsKey(key);
        }

        synchronized void putString(String key, String value) {
            if (key == null) {
                return;
            }
            if (value == null) {
                data.remove(key);
            } else {
                data.put(key, value);
            }
            save();
        }

        synchronized void putBoolean(String key, boolean value) {
            putString(key, value ? "true" : "false");
        }

        synchronized void putAll(Map<String, String> values) {
            data.putAll(values);
            save();
        }

        synchronized void remove(String key) {
            if (data.remove(key) != null) {
                save();
            }
        }

        private void load() {
            if (!file.isFile()) {
                return;
            }
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) Math.max(0, file.length())];
                int n = in.read(bytes);
                if (n <= 0) {
                    return;
                }
                JSONObject root = new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    data.put(key, String.valueOf(root.opt(key)));
                }
            } catch (Throwable ignored) {
                // A corrupt state file is a fresh state; the user can select again.
            }
        }

        private void save() {
            try {
                JSONObject root = new JSONObject();
                for (Map.Entry<String, String> e : data.entrySet()) {
                    root.put(e.getKey(), e.getValue());
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
            } catch (Throwable ignored) {
                // The in-memory state still serves this process.
            }
        }
    }
}
