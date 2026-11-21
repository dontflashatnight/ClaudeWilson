package com.walter.claudewilson;

import android.content.Context;
import android.text.TextUtils;

import com.walter.claudewilson.model.AtomicTextFile;
import com.walter.claudewilson.model.PersonaSalt;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
    private static final String DEVICE_HOOK_ONLY = "ovr_dev_hook.";
    private static final String AID_OVERRIDE = "ovr_aid.";
    private static final String SEED = "root_seed.";
    private static final String HISTORY = "root_hist.";
    private static final String CARRIER = "root_carrier.";
    private static final String OP = "root_op.";
    private static final String KEEP_REAL_SIM = "root_keep_sim.";
    private static final String CSC = "root_csc.";
    private static final String CC_HINT = "root_cc_hint.";
    private static final String LOCATION = "root_loc.";
    private static final String LOC_LAT = "root_loc_lat.";
    private static final String LOC_LNG = "root_loc_lng.";
    private static final String LOC_CITY = "root_loc_city.";
    private static final String LOC_ACC = "root_loc_acc.";
    private static final String LOC_ALT = "root_loc_alt.";
    private static final String WIFI_ON = "root_wifi_on.";
    private static final String WIFI_SALT = "root_wifi_salt.";
    private static final String WIFI_SSID = "root_wifi_ssid.";
    private static final String WIFI_BSSID = "root_wifi_bssid.";
    private static final String BLE_ON = "root_ble_on.";
    private static final String BLE_EMPTY = "root_ble_empty.";
    private static final String BLE_SALT = "root_ble_salt.";
    private static final String BLE_BONDED = "root_ble_bonded.";
    private static final String SENS_ON = "root_sens_on.";
    private static final String SENS_PRESET = "root_sens_preset.";
    private static final String APP_VIS_MODE = "root_appvis_mode.";
    private static final String APP_VIS_PKGS = "root_appvis_pkgs.";
    private static final String APP_VIS_EXTRA_HIDDEN = "root_appvis_extra.";
    private static final String APP_VIS_EXCL_SYS = "root_appvis_excl_sys.";
    private static final String APP_VIS_STRICT = "root_appvis_strict.";
    private static final String APP_VIS_HIDE_DEV = "root_appvis_hdev.";
    private static final String APP_VIS_HIDE_A11Y = "root_appvis_ha11y.";
    private static final String APP_VIS_HIDE_IME = "root_appvis_hime.";
    private static final String APP_VIS_PRESET_GROUPS = "root_appvis_pgroups.";
    private static final String APP_VIS_PRESET = "root_appvis_preset.";
    private static final String PROFILE_SALT = "root_psalt.";
    private static final String SPACE_SEED = "root_space_seed.";
    private static final String SALT_VER = "root_salt_ver.";
    private static final String BUILD_PIN = "root_build_pin.";
    private static final String UNLISTED_ID = "root_unlisted.";
    private static final String IPV6_SPOOF_ENABLED = "global_ipv6_spoof_enabled";
    private static final String IPV6_PREFIX = "root_ipv6pfx.";
    private static final String IPV6_MOBILE_PREFIX = "root_ipv6mobpfx.";
    private static final String IPV6_ISO = "root_ipv6iso.";
    private static final String IPV6_CARRIER = "root_ipv6car.";
    private static final String INJECT_SIG = "inject_sig.";
    private static final String REGION_ISO = "space_iso.";
    private static final String REGION_ZONE = "space_zone.";
    private static final String REGION_LOCALE = "space_locale.";
    private static final String LAST_SELECTED_USER = "last_selected_user";
    private static final String LEGACY_REGION_MIGRATED = "space_region_migrated";
    private static final String USERS = "root_users";
    private static final String LEGACY_AID = "root_aid";
    private static final String LEGACY_DEVICE = "root_dev";
    private static final String LEGACY_SEED = "root_seed";
    private static final String FILE = "persona-state.json";

    private static final Map<String, Store> STORES = new LinkedHashMap<String, Store>();

    private final Store store;

    PersonaState(Context context) {
        store = storeFor(new File(context.getApplicationContext().getFilesDir(), FILE));
        migrateLegacy();
    }

    private static Store storeFor(File file) {
        String key = file.getAbsolutePath();
        synchronized (STORES) {
            Store existing = STORES.get(key);
            if (existing == null) {
                existing = new Store(file);
                STORES.put(key, existing);
            }
            return existing;
        }
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

    /** True when this user space already holds pre-v2 state from an earlier run. */
    boolean hasLegacySpaceState(String user) {
        return store.hasNonEmptyPrefix(PROFILE_SALT + user + ".")
                || store.contains(SEED + user)
                || store.contains(DEVICE + user)
                || store.contains(REGION_ISO + user)
                || store.contains(INJECT_SIG + user)
                || store.contains(IPV6_ISO + user)
                || store.contains(AID + user);
    }

    /**
     * Salt algorithm version locked to this space: already-used spaces stay on {@code v1} across
     * app upgrades so their established identity never shifts; brand-new spaces start on {@code v2}.
     */
    int saltVersion(String user) {
        String key = SALT_VER + user;
        int persisted = store.getInt(key, 0);
        if (persisted == PersonaSalt.SALT_V1 || persisted == PersonaSalt.SALT_V2) {
            return persisted;
        }
        int resolved = PersonaSalt.resolveSaltVersion(0, hasLegacySpaceState(user));
        store.putString(key, String.valueOf(resolved));
        return resolved;
    }

    /**
     * Persistent non-zero SecureRandom base salt for one Android user space.
     *
     * <p>Generated lazily on first read so two fresh v2 spaces on the default device model never
     * collide on {@code seed = 0L} when deriving build variants or initial region snapshots.
     */
    long spaceSeed(String user) {
        String key = SPACE_SEED + user;
        long value = store.getLong(key, 0L);
        if (value == 0L) {
            value = PersonaSalt.nonZero(new SecureRandom().nextLong());
            store.putString(key, String.valueOf(value));
        }
        return value;
    }

    /** Effective seed passed into {@code PersonaSynth.cross}, preserving v1 for legacy spaces. */
    long effectiveDeviceSeed(String user) {
        int ver = saltVersion(user);
        if (ver <= PersonaSalt.SALT_V1) {
            return PersonaSalt.deviceCrossSeed(ver, 0L, seed(user));
        }
        return PersonaSalt.deviceCrossSeed(ver, spaceSeed(user), seed(user));
    }

    /** Frozen build tuple {@code [fingerprint, id, incremental, securityPatch]} for (user, model). */
    String[] buildPin(String user, String modelIdentity) {
        String key = BUILD_PIN + user + "."
                + com.walter.claudewilson.model.ProfileIdentity.key(modelIdentity);
        String raw = store.get(key, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split("\u001F", -1);
        return parts.length == 4 ? parts : null;
    }

    /** Freezes synthesized or adopted build fields so future app updates never drift them. */
    void putBuildPin(String user, String modelIdentity, String[] dv) {
        if (dv == null || dv.length < 14 || dv[8] == null || dv[8].isEmpty()
                || dv[9] == null || dv[9].isEmpty()) {
            return;
        }
        String key = BUILD_PIN + user + "."
                + com.walter.claudewilson.model.ProfileIdentity.key(modelIdentity);
        String packed = dv[8] + "\u001F" + dv[9] + "\u001F"
                + (dv[10] == null ? "" : dv[10]) + "\u001F"
                + (dv[13] == null ? "" : dv[13]);
        store.putString(key, packed);
    }

    /** Clears frozen build pins for {@code user} when the user explicitly selects a new model. */
    void clearBuildPins(String user) {
        store.removeByPrefix(BUILD_PIN + user + ".");
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

    /**
     * Device-wide persistent IPv6 mode toggle: defaults to {@code false} (IPv6 disabled across
     * all interfaces and dropped in target hooks; no regional spoofed IPv6 prefix assigned).
     */
    boolean ipv6SpoofEnabled() {
        return store.getBoolean(IPV6_SPOOF_ENABLED, false);
    }

    void putIpv6SpoofEnabled(boolean enabled) {
        store.putBoolean(IPV6_SPOOF_ENABLED, enabled);
    }

    /** Per-space IPv6 prefix chosen at publish time; rotates on each publish. */
    String ipv6Prefix(String user) {
        return store.get(IPV6_PREFIX + user, null);
    }

    void putIpv6Prefix(String user, String prefix) {
        store.putString(IPV6_PREFIX + user, prefix);
    }

    /** Per-space cellular IPv6 prefix aligned with the SIM carrier. */
    String ipv6MobilePrefix(String user) {
        return store.get(IPV6_MOBILE_PREFIX + user, null);
    }

    void putIpv6MobilePrefix(String user, String prefix) {
        store.putString(IPV6_MOBILE_PREFIX + user, prefix);
    }

    String ipv6Iso(String user) {
        return store.get(IPV6_ISO + user, null);
    }

    void putIpv6Iso(String user, String iso) {
        store.putString(IPV6_ISO + user, iso);
    }

    String ipv6Carrier(String user) {
        return store.get(IPV6_CARRIER + user, null);
    }

    void putIpv6Carrier(String user, String carrier) {
        store.putString(IPV6_CARRIER + user, carrier);
    }

    /** Writes all 4 IPv6 state keys in a single atomic disk sync. */
    void putIpv6State(String user, String prefix, String mobilePrefix,
                      String iso, String carrier) {
        Map<String, String> updates = new LinkedHashMap<String, String>();
        updates.put(IPV6_PREFIX + user, prefix);
        updates.put(IPV6_MOBILE_PREFIX + user, mobilePrefix);
        updates.put(IPV6_ISO + user, iso);
        updates.put(IPV6_CARRIER + user, carrier);
        store.putAll(updates);
    }

    /** Preserving a non-restricted physical SIM's real trio defaults on. */
    boolean keepRealSim(String user) {
        return store.getBoolean(KEEP_REAL_SIM + user, true);
    }

    void putKeepRealSim(String user, boolean enabled) {
        store.putBoolean(KEEP_REAL_SIM + user, enabled);
    }

    /** Synthetic location defaults on: a region persona without a place is only half an identity. */
    boolean location(String user) {
        return store.getBoolean(LOCATION + user, true);
    }

    void putLocation(String user, boolean enabled) {
        store.putBoolean(LOCATION + user, enabled);
    }

    /** Location simulation mode: "fine" (±5m), "coarse" (±3km), or "drift" (±15m). */
    String locationAccuracyMode(String user) {
        String v = store.get(LOC_ACC + user, "fine");
        return v == null || v.isEmpty() ? "fine" : v;
    }

    void putLocationAccuracyMode(String user, String mode) {
        store.putString(LOC_ACC + user, mode == null || mode.isEmpty() ? "fine" : mode);
    }

    boolean hasLocationAltitude(String user) {
        return store.contains(LOC_ALT + user);
    }

    double locationAltitude(String user) {
        return store.contains(LOC_ALT + user)
                ? Double.longBitsToDouble(store.getLong(LOC_ALT + user, 0L))
                : Double.NaN;
    }

    void putLocationAltitude(String user, double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) {
            store.remove(LOC_ALT + user);
        } else {
            store.putString(LOC_ALT + user, String.valueOf(Double.doubleToRawLongBits(meters)));
        }
    }

    // ---- Wi-Fi neighbourhood overrides ----
    boolean wifiEnabled(String user) {
        return store.getBoolean(WIFI_ON + user, true);
    }

    void putWifiEnabled(String user, boolean enabled) {
        store.putBoolean(WIFI_ON + user, enabled);
    }

    long wifiSalt(String user) {
        return store.getLong(WIFI_SALT + user, 0L);
    }

    void putWifiSalt(String user, long salt) {
        if (salt <= 0L) {
            store.remove(WIFI_SALT + user);
        } else {
            store.putString(WIFI_SALT + user, String.valueOf(salt));
        }
    }

    void rerollWifi(String user) {
        putWifiSalt(user, wifiSalt(user) + 1L);
    }

    String wifiCustomSsid(String user) {
        return store.get(WIFI_SSID + user, null);
    }

    void putWifiCustomSsid(String user, String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) {
            store.remove(WIFI_SSID + user);
        } else {
            store.putString(WIFI_SSID + user, ssid.trim());
        }
    }

    String wifiCustomBssid(String user) {
        return store.get(WIFI_BSSID + user, null);
    }

    void putWifiCustomBssid(String user, String bssid) {
        if (bssid == null || bssid.trim().isEmpty()) {
            store.remove(WIFI_BSSID + user);
        } else {
            store.putString(WIFI_BSSID + user, bssid.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    void resetWifi(String user) {
        store.putBoolean(WIFI_ON + user, true);
        store.remove(WIFI_SSID + user);
        store.remove(WIFI_BSSID + user);
        store.remove(WIFI_SALT + user);
    }

    // ---- BLE neighbourhood overrides ----
    boolean bleEnabled(String user) {
        return store.getBoolean(BLE_ON + user, true);
    }

    void putBleEnabled(String user, boolean enabled) {
        store.putBoolean(BLE_ON + user, enabled);
    }

    boolean bleEmptyMode(String user) {
        return store.getBoolean(BLE_EMPTY + user, false);
    }

    void putBleEmptyMode(String user, boolean empty) {
        store.putBoolean(BLE_EMPTY + user, empty);
    }

    long bleSalt(String user) {
        return store.getLong(BLE_SALT + user, 0L);
    }

    void putBleSalt(String user, long salt) {
        if (salt <= 0L) {
            store.remove(BLE_SALT + user);
        } else {
            store.putString(BLE_SALT + user, String.valueOf(salt));
        }
    }

    void rerollBle(String user) {
        putBleSalt(user, bleSalt(user) + 1L);
    }

    String bleCustomBonded(String user) {
        return store.get(BLE_BONDED + user, null);
    }

    void putBleCustomBonded(String user, String name) {
        if (name == null || name.trim().isEmpty()) {
            store.remove(BLE_BONDED + user);
        } else {
            store.putString(BLE_BONDED + user, name.trim());
        }
    }

    void resetBle(String user) {
        store.putBoolean(BLE_ON + user, true);
        store.putBoolean(BLE_EMPTY + user, false);
        store.remove(BLE_BONDED + user);
        store.remove(BLE_SALT + user);
    }

    // ---- Sensor baseline overrides ----
    boolean sensorsEnabled(String user) {
        return store.getBoolean(SENS_ON + user, true);
    }

    void putSensorsEnabled(String user, boolean enabled) {
        store.putBoolean(SENS_ON + user, enabled);
    }

    String sensorPreset(String user) {
        String p = store.get(SENS_PRESET + user, com.walter.claudewilson.model.SensorProfile.PRESET_AUTO);
        return p == null || p.isEmpty() ? com.walter.claudewilson.model.SensorProfile.PRESET_AUTO : p;
    }

    void putSensorPreset(String user, String preset) {
        store.putString(SENS_PRESET + user,
                preset == null || preset.isEmpty()
                        ? com.walter.claudewilson.model.SensorProfile.PRESET_AUTO : preset);
    }

    private static final String EMPTY_SET_SENTINEL = "__none__";

    // ---- Target app-list visibility overrides ----
    String appVisMode(String user) {
        return com.walter.claudewilson.model.AppVisibilityPolicy.normalizeMode(
                store.get(APP_VIS_MODE + user,
                        com.walter.claudewilson.model.AppVisibilityPolicy.MODE_ALLOWLIST));
    }

    void putAppVisMode(String user, String mode) {
        store.putString(APP_VIS_MODE + user,
                com.walter.claudewilson.model.AppVisibilityPolicy.normalizeMode(mode));
    }

    java.util.Set<String> appVisPackages(String user) {
        String raw = store.get(APP_VIS_PKGS + user, "");
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    void putAppVisPackages(String user, java.util.Set<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) {
            store.remove(APP_VIS_PKGS + user);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.trim());
        }
        if (sb.length() == 0) {
            store.remove(APP_VIS_PKGS + user);
        } else {
            store.putString(APP_VIS_PKGS + user, sb.toString());
        }
    }

    /** HMA-style "Exclude System Apps" in allowlist mode; defaults ON so OEM services never break. */
    boolean appVisExcludeSystem(String user) {
        return store.getBoolean(APP_VIS_EXCL_SYS + user, true);
    }

    void putAppVisExcludeSystem(String user, boolean exclude) {
        store.putBoolean(APP_VIS_EXCL_SYS + user, exclude);
    }

    /** HMA strict side-channel shields (filesystem, LauncherApps, Accessibility, IME, pm exec). */
    boolean appVisStrict(String user) {
        return store.getBoolean(APP_VIS_STRICT + user, true);
    }

    void putAppVisStrict(String user, boolean strict) {
        store.putBoolean(APP_VIS_STRICT + user, strict);
    }

    boolean hasExplicitAppVisExtra(String user) {
        return store.contains(APP_VIS_EXTRA_HIDDEN + user);
    }

    java.util.Set<String> appVisExtraHidden(String user) {
        return readCsvSet(APP_VIS_EXTRA_HIDDEN + user);
    }

    void putAppVisExtraHidden(String user, java.util.Set<String> pkgs) {
        writeCsvSetExplicit(APP_VIS_EXTRA_HIDDEN + user, pkgs);
    }

    boolean appVisHideDev(String user) {
        return store.getBoolean(APP_VIS_HIDE_DEV + user, true);
    }

    void putAppVisHideDev(String user, boolean hide) {
        store.putBoolean(APP_VIS_HIDE_DEV + user, hide);
    }

    boolean appVisHideA11y(String user) {
        return store.getBoolean(APP_VIS_HIDE_A11Y + user, true);
    }

    void putAppVisHideA11y(String user, boolean hide) {
        store.putBoolean(APP_VIS_HIDE_A11Y + user, hide);
    }

    boolean appVisHideIme(String user) {
        return store.getBoolean(APP_VIS_HIDE_IME + user, true);
    }

    void putAppVisHideIme(String user, boolean hide) {
        store.putBoolean(APP_VIS_HIDE_IME + user, hide);
    }

    /** Defaults to all 8 HMA detection preset groups enabled until explicitly modified. */
    java.util.Set<String> appVisPresetGroups(String user) {
        String key = APP_VIS_PRESET_GROUPS + user;
        if (!store.contains(key)) {
            return new java.util.LinkedHashSet<String>(HmaPresetScanner.ALL_GROUPS);
        }
        return readCsvSet(key);
    }

    void putAppVisPresetGroups(String user, java.util.Set<String> groups) {
        writeCsvSetExplicit(APP_VIS_PRESET_GROUPS + user, groups);
    }

    private java.util.Set<String> readCsvSet(String key) {
        String raw = store.get(key, "");
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (raw == null || raw.isEmpty() || EMPTY_SET_SENTINEL.equals(raw)) {
            return out;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && !EMPTY_SET_SENTINEL.equals(p)) out.add(p);
        }
        return out;
    }

    private void writeCsvSetExplicit(String key, java.util.Set<String> items) {
        if (items == null || items.isEmpty()) {
            store.putString(key, EMPTY_SET_SENTINEL);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : items) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.trim());
        }
        store.putString(key, sb.length() == 0 ? EMPTY_SET_SENTINEL : sb.toString());
    }

    /** Optional label of the HMA preset currently applied to this space, or empty if custom. */
    String appVisPreset(String user) {
        String p = store.get(APP_VIS_PRESET + user, "");
        return p == null ? "" : p.trim();
    }

    void putAppVisPreset(String user, String presetName) {
        if (presetName == null || presetName.trim().isEmpty()) {
            store.remove(APP_VIS_PRESET + user);
        } else {
            store.putString(APP_VIS_PRESET + user, presetName.trim());
        }
    }

    /** Applies a complete HMA visibility profile to one space. */
    void applyAppVisToUser(String user, String mode, java.util.Set<String> pkgs,
                           boolean excludeSystem, boolean strict, String presetName) {
        putAppVisMode(user, mode);
        putAppVisPackages(user, pkgs);
        putAppVisExcludeSystem(user, excludeSystem);
        putAppVisStrict(user, strict);
        putAppVisPreset(user, presetName);
    }

    /** Batch-applies an HMA visibility preset to the active space and every known persona space. */
    int applyAppVisToAllUsers(String activeUser, String mode, java.util.Set<String> pkgs,
                              boolean excludeSystem, boolean strict, String presetName) {
        java.util.Set<String> targets = new java.util.LinkedHashSet<String>();
        if (activeUser != null && !activeUser.trim().isEmpty()) {
            targets.add(activeUser.trim());
        }
        List<String> allUsers = users();
        if (allUsers != null) {
            for (String u : allUsers) {
                if (u != null && !u.trim().isEmpty()) {
                    targets.add(u.trim());
                }
            }
        }
        if (targets.isEmpty()) {
            targets.add("0");
        }
        for (String u : targets) {
            applyAppVisToUser(u, mode, pkgs, excludeSystem, strict, presetName);
        }
        return targets.size();
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
        Map<String, String> updates = new LinkedHashMap<String, String>();
        updates.put(LOC_LAT + user, null);
        updates.put(LOC_LNG + user, null);
        updates.put(LOC_CITY + user, null);
        store.putAll(updates);
    }

    /**
     * Atomically clears every country-bound manual override when switching a space to a different
     * region, preventing SIM carrier, GPS pin/altitude, custom Wi-Fi SSID/BSSID, country hint, or
     * CSC target from leaking across countries.
     */
    void clearRegionBoundOverrides(String user) {
        Map<String, String> updates = new LinkedHashMap<String, String>();
        updates.put(OP + user, null);
        updates.put(CARRIER + user, null);
        updates.put(LOC_LAT + user, null);
        updates.put(LOC_LNG + user, null);
        updates.put(LOC_CITY + user, null);
        updates.put(LOC_ALT + user, null);
        updates.put(WIFI_SSID + user, null);
        updates.put(WIFI_BSSID + user, null);
        updates.put(CC_HINT + user, null);
        updates.put(CSC + user, null);
        store.putAll(updates);
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

    /** Persists only the AID override without disturbing device or custom-device fields. */
    void putAid(String user, String aid) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(AID + user, aid);
        values.put(AID_OVERRIDE + user, "true");
        values.put(USERS, withUser(users(), user));
        store.putAll(values);
    }

    /*
     * Overrides are opt-in. A stored value alone does not mean the user asked for a target: earlier
     * builds wrote one whenever a row was touched, and those leftovers kept reappearing as an
     * "expected" value the user never chose. The flag records a deliberate choice.
     */

    boolean hasDeviceOverride(String user) {
        return store.getBoolean(DEVICE_OVERRIDE + user, false);
    }

    /** True when the model override applies to Hook payload only (no system ro.* / soft reboot). */
    boolean deviceHookOnly(String user) {
        return store.getBoolean(DEVICE_HOOK_ONLY + user, false);
    }

    void putDeviceHookOnly(String user, boolean hookOnly) {
        store.putBoolean(DEVICE_HOOK_ONLY + user, hookOnly);
    }

    void markDeviceOverride(String user) {
        store.putBoolean(DEVICE_OVERRIDE + user, true);
    }

    void clearDeviceOverride(String user) {
        store.remove(DEVICE_OVERRIDE + user);
        store.remove(DEVICE_HOOK_ONLY + user);
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

    /** Per-space bound region ISO (e.g. "jp"), or null when the space runs on native device state. */
    String regionIso(String user) {
        String v = store.get(REGION_ISO + user, null);
        return v == null || v.isEmpty() ? null : v;
    }

    String regionZone(String user) {
        String v = store.get(REGION_ZONE + user, null);
        return v == null || v.isEmpty() ? null : v;
    }

    String regionLocale(String user) {
        String v = store.get(REGION_LOCALE + user, null);
        return v == null || v.isEmpty() ? null : v;
    }

    /** Atomically binds a region selection to one user space. */
    void putRegion(String user, String iso, String zone, String locale) {
        Map<String, String> updates = new LinkedHashMap<String, String>();
        updates.put(REGION_ISO + user, iso);
        updates.put(REGION_ZONE + user, zone);
        updates.put(REGION_LOCALE + user, locale);
        updates.put(USERS, withUser(users(), user));
        store.putAll(updates);
    }

    /** Clears the space's bound region back to native device state. */
    void clearRegion(String user) {
        Map<String, String> updates = new LinkedHashMap<String, String>();
        updates.put(REGION_ISO + user, null);
        updates.put(REGION_ZONE + user, null);
        updates.put(REGION_LOCALE + user, null);
        store.putAll(updates);
    }

    /** Last user space selected in the UI, or null if never switched. */
    String lastSelectedUser() {
        String v = store.get(LAST_SELECTED_USER, null);
        return v == null || v.isEmpty() ? null : v;
    }

    void putLastSelectedUser(String user) {
        if (user == null || user.trim().isEmpty()) {
            return;
        }
        store.putString(LAST_SELECTED_USER, user.trim());
    }

    /** One-shot gate so legacy global EnvSession migration cannot contaminate fresh Native spaces. */
    boolean legacyRegionMigrated() {
        return store.getBoolean(LEGACY_REGION_MIGRATED, false);
    }

    void markLegacyRegionMigrated() {
        store.putBoolean(LEGACY_REGION_MIGRATED, true);
    }

    /** True if any Android user space already has a bound region ISO recorded. */
    boolean hasAnyBoundRegion() {
        return store.hasNonEmptyPrefix(REGION_ISO);
    }

    /** Blocks briefly until queued async disk writes finish (called before zygote soft reboot). */
    static void flushPending() {
        Store.flushPending();
    }

    /** Immutable snapshot of this space's bound identity and region configuration. */
    com.walter.claudewilson.model.SpaceProfile profile(String user) {
        return new com.walter.claudewilson.model.SpaceProfile(
                user,
                regionIso(user),
                regionZone(user),
                regionLocale(user),
                keepRealSim(user),
                carrier(user),
                op(user),
                device(user),
                hasDeviceOverride(user),
                aid(user),
                hasAidOverride(user),
                csc(user),
                countryHint(user),
                location(user),
                ipv6Prefix(user));
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

        synchronized boolean hasNonEmptyPrefix(String prefix) {
            for (Map.Entry<String, String> e : data.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(prefix)
                        && e.getValue() != null && !e.getValue().trim().isEmpty()) {
                    return true;
                }
            }
            return false;
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
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getKey() == null) continue;
                if (e.getValue() == null) data.remove(e.getKey());
                else data.put(e.getKey(), e.getValue());
            }
            save();
        }

        synchronized void remove(String key) {
            if (data.remove(key) != null) {
                save();
            }
        }

        synchronized void removeByPrefix(String prefix) {
            if (prefix == null || prefix.isEmpty()) {
                return;
            }
            boolean changed = false;
            Iterator<String> it = data.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null && k.startsWith(prefix)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                save();
            }
        }

        private void load() {
            if (!file.isFile()) {
                return;
            }
            try {
                String text = AtomicTextFile.readUtf8(file);
                if (text == null || text.isEmpty()) {
                    return;
                }
                JSONObject root = new JSONObject(text);
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    data.put(key, String.valueOf(root.opt(key)));
                }
            } catch (Throwable ignored) {
                // A corrupt state file is a fresh state; the user can select again.
            }
        }

        private static final java.util.concurrent.ExecutorService IO =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "persona-store-io");
                    t.setDaemon(true);
                    return t;
                });

        static void flushPending() {
            try {
                IO.submit(() -> { }).get(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            }
        }

        private void save() {
            final byte[] bytes;
            try {
                JSONObject root = new JSONObject();
                for (Map.Entry<String, String> e : data.entrySet()) {
                    root.put(e.getKey(), e.getValue());
                }
                bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                return;
            }
            IO.execute(() -> {
                try {
                    AtomicTextFile.writeUtf8(file, bytes);
                } catch (Throwable ignored) {
                    // The in-memory state still serves this process.
                }
            });
        }
    }
}
