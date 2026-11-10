package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Persona state, keyed by Android user.
 *
 * <p>The android id is a per-user value: each user (persona space) has its own SSAID row in
 * {@code settings_ssaid.xml}. Keeping one global record meant switching users still showed — and
 * re-injected — the previous user's id.
 */
final class PersonaState {
    private static final String AID = "root_aid.";
    private static final String DEVICE = "root_dev.";
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
    private static final String INJECT_SIG = "inject_sig.";
    private static final String USERS = "root_users";
    private static final String LEGACY_AID = "root_aid";
    private static final String LEGACY_DEVICE = "root_dev";
    private static final String LEGACY_SEED = "root_seed";

    private final SharedPreferences prefs;

    PersonaState(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("apply", 0);
        migrateLegacy();
    }

    /** Before the per-user keys existed the values were global; they belong to user 0. */
    private void migrateLegacy() {
        if (!prefs.contains(LEGACY_AID) && !prefs.contains(LEGACY_DEVICE)) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains(DEVICE + "0")) {
            editor.putString(AID + "0", prefs.getString(LEGACY_AID, ""))
                    .putInt(DEVICE + "0", prefs.getInt(LEGACY_DEVICE, 0))
                    .putLong(SEED + "0", prefs.getLong(LEGACY_SEED, 0L))
                    .putString(USERS, withUser(users(), "0"));
        }
        editor.remove(LEGACY_AID).remove(LEGACY_DEVICE).remove(LEGACY_SEED).apply();
    }

    String aid(String user) {
        return prefs.getString(AID + user, null);
    }

    int device(String user) {
        return prefs.getInt(DEVICE + user, 0);
    }

    long seed(String user) {
        return prefs.getLong(SEED + user, 0L);
    }

    /** Per-space SIM carrier override, or null when the region profile should be used. */
    String carrier(String user) {
        return prefs.getString(CARRIER + user, null);
    }

    void putCarrier(String user, String carrier) {
        prefs.edit().putString(CARRIER + user, carrier).apply();
    }

    /** Drop the override: the selected region's carrier profile wins again. */
    void clearCarrier(String user) {
        prefs.edit().remove(CARRIER + user).apply();
    }

    /** Per-space SIM operator (mccmnc) override, or null when the region profile should be used. */
    String op(String user) {
        return prefs.getString(OP + user, null);
    }

    void putOp(String user, String op) {
        prefs.edit().putString(OP + user, op).apply();
    }

    /** Drop the override: the selected region's operator profile wins again. */
    void clearOp(String user) {
        prefs.edit().remove(OP + user).apply();
    }

    /** Target product name the user asked the CSC row to write, or null when unassigned. */
    String csc(String user) {
        return prefs.getString(CSC + user, null);
    }

    void putCsc(String user, String productName) {
        prefs.edit().putString(CSC + user, productName).apply();
    }

    /** Forget the target: the row shows the device's own tag again. */
    void clearCsc(String user) {
        prefs.edit().remove(CSC + user).apply();
    }

    /** Target-visible country_code_hint override, or null when the region ISO should be used. */
    String countryHint(String user) {
        return prefs.getString(CC_HINT + user, null);
    }

    void putCountryHint(String user, String iso) {
        prefs.edit().putString(CC_HINT + user, iso).apply();
    }

    void clearCountryHint(String user) {
        prefs.edit().remove(CC_HINT + user).apply();
    }

    /** Synthetic location defaults on: a region persona without a place is only half an identity. */
    boolean location(String user) {
        return prefs.getBoolean(LOCATION + user, true);
    }

    void putLocation(String user, boolean enabled) {
        prefs.edit().putBoolean(LOCATION + user, enabled).apply();
    }

    /** A map-picked point for this space; absent means "follow the region centre". */
    boolean hasLocationPoint(String user) {
        return prefs.contains(LOC_LAT + user) && prefs.contains(LOC_LNG + user);
    }

    double locationLatitude(String user) {
        return Double.longBitsToDouble(prefs.getLong(LOC_LAT + user, 0L));
    }

    double locationLongitude(String user) {
        return Double.longBitsToDouble(prefs.getLong(LOC_LNG + user, 0L));
    }

    String locationCity(String user) {
        return prefs.getString(LOC_CITY + user, "");
    }

    void putLocationPoint(String user, double latitude, double longitude, String city) {
        prefs.edit()
                .putLong(LOC_LAT + user, Double.doubleToRawLongBits(latitude))
                .putLong(LOC_LNG + user, Double.doubleToRawLongBits(longitude))
                .putString(LOC_CITY + user, city == null ? "" : city)
                .apply();
    }

    void clearLocationPoint(String user) {
        prefs.edit().remove(LOC_LAT + user).remove(LOC_LNG + user).remove(LOC_CITY + user).apply();
    }

    /** Drop the AID override: the row falls back to the device's own SSAID. */
    void clearAid(String user) {
        prefs.edit().remove(AID + user).remove(AID_OVERRIDE + user).apply();
    }

    /*
     * Overrides are opt-in. A stored value alone does not mean the user asked for a target: earlier
     * builds wrote one whenever a row was touched, and those leftovers kept reappearing as an
     * "expected" value the user never chose. The flag records a deliberate choice.
     */

    boolean hasDeviceOverride(String user) {
        return prefs.getBoolean(DEVICE_OVERRIDE + user, false);
    }

    void markDeviceOverride(String user) {
        prefs.edit().putBoolean(DEVICE_OVERRIDE + user, true).apply();
    }

    void clearDeviceOverride(String user) {
        prefs.edit().remove(DEVICE_OVERRIDE + user).apply();
    }

    boolean hasAidOverride(String user) {
        return prefs.getBoolean(AID_OVERRIDE + user, false);
    }

    void markAidOverride(String user) {
        prefs.edit().putBoolean(AID_OVERRIDE + user, true).apply();
    }

    void clearAidOverride(String user) {
        prefs.edit().remove(AID_OVERRIDE + user).apply();
    }

    /** Recent persona picks for this space, oldest first, comma-separated. */
    String history(String user) {
        return prefs.getString(HISTORY + user, "");
    }

    /**
     * Signature of the payload the target process was last injected with, or null when nothing
     * was injected for this space.
     *
     * <p>The panel has rows that only the injection layer can move (the SSAID, the visited-network
     * trio). Their mark is meaningful only while the injected payload still equals what the rows
     * expect: switching region changes the payload, so the old signature stops matching and the
     * mark falls back to what the device itself reports.
     */
    String injectedSig(String user) {
        return prefs.getString(INJECT_SIG + user, null);
    }

    void markInjected(String user, String sig) {
        if (sig != null) {
            prefs.edit().putString(INJECT_SIG + user, sig).apply();
        }
    }

    void clearInjected(String user) {
        prefs.edit().remove(INJECT_SIG + user).apply();
    }

    void putHistory(String user, String history) {
        prefs.edit().putString(HISTORY + user, history).apply();
    }

    void put(String user, int device, String aid, long seed) {
        prefs.edit()
                .putInt(DEVICE + user, device)
                .putString(AID + user, aid)
                .putLong(SEED + user, seed)
                .putString(USERS, withUser(users(), user))
                .apply();
    }

    /** Users this app has written persona state for, in the order they were first touched. */
    List<String> users() {
        List<String> out = new ArrayList<String>();
        String raw = prefs.getString(USERS, "");
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
}
