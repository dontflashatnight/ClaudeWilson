package com.walter.claudewilson.hook;

import com.walter.claudewilson.model.BleProfile;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.model.WifiProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persona payload read from the per-target file the control app writes through the root shell. */
final class HookConfig {
    static final String DIR = "/data/local/tmp/claudewilson";

    final String targetId;
    final String path;
    final String target;
    final String countryCodeHint;
    final String simOperator;
    final String simCountryIso;
    final String simOperatorName;
    final String netOperator;
    final String netCountryIso;
    final String netOperatorName;
    final int simCarrierId;
    final String androidId;
    final String locale;
    final List<String> localeList;
    final String tz;
    final boolean hideAccounts;
    final boolean locationEnabled;
    final double latitude;
    final double longitude;
    final String locationCity;
    final long locationSeed;
    final List<WifiProfile.Ap> wifi;
    final List<BleProfile.Device> ble;
    final boolean sensorsEnabled;
    final float[] sensorAccel;
    final float[] sensorGyro;
    final float[] sensorMag;
    final float sensorLight;
    final float sensorPressure;
    final float sensorProximity;
    final Map<String, String> build;
    final Map<String, String> props;

    private HookConfig(String targetId, String path, JSONObject o) {
        this.targetId = targetId;
        this.path = path;
        target = o.optString("target", "");
        countryCodeHint = o.optString("countryCodeHint", "");
        simOperator = o.optString("simOperator", "");
        simCountryIso = o.optString("simCountryIso", "");
        simOperatorName = o.optString("simOperatorName", "");
        netOperator = o.optString("netOperator", simOperator);
        netCountryIso = o.optString("netCountryIso", simCountryIso);
        netOperatorName = o.optString("netOperatorName", simOperatorName);
        simCarrierId = o.optInt("simCarrierId", -1);
        androidId = o.optString("androidId", "");
        JSONObject localeObject = o.optJSONObject("locale");
        locale = localeObject != null
                ? localeObject.optString("tag", "")
                : o.optString("locale", "");
        localeList = localeList(locale, localeObject, o.optJSONArray("localeList"));
        tz = o.optString("tz", "");
        hideAccounts = o.optBoolean("hideAccounts", false);
        JSONObject location = o.optJSONObject("location");
        locationEnabled = location != null
                ? location.optBoolean("enabled", false)
                : o.optBoolean("locationEnabled", false);
        latitude = location != null
                ? location.optDouble("latitude", Double.NaN)
                : o.optDouble("latitude", Double.NaN);
        longitude = location != null
                ? location.optDouble("longitude", Double.NaN)
                : o.optDouble("longitude", Double.NaN);
        locationCity = location != null
                ? location.optString("city", "")
                : o.optString("locationCity", "");
        locationSeed = location != null
                ? location.optLong("seed", 0L)
                : o.optLong("locationSeed", 0L);
        wifi = parseWifi(o.optJSONArray("wifi"));
        ble = parseBle(o.optJSONArray("ble"));
        sensorsEnabled = o.optBoolean("sensorsEnabled", false);
        JSONObject sensors = o.optJSONObject("sensors");
        sensorAccel = floats(sensors, "accelerometer");
        sensorGyro = floats(sensors, "gyroscope");
        sensorMag = floats(sensors, "magnetometer");
        sensorLight = sensors == null ? 120.0f : (float) sensors.optDouble("light", 120.0d);
        sensorPressure = sensors == null ? 1013.25f
                : (float) sensors.optDouble("pressure", 1013.25d);
        sensorProximity = sensors == null ? 0.0f
                : (float) sensors.optDouble("proximity", 0.0d);
        build = map(o.optJSONObject("build"));
        props = map(o.optJSONObject("props"));
    }

    /**
     * Canonical payload first. The legacy single-target file is a fallback only while the
     * canonical file does not exist; a corrupt canonical file must not silently resurrect an
     * old persona.
     */
    static HookConfig load(TargetSpec spec) {
        if (spec == null || !spec.hooksEnabled()) {
            return null;
        }
        HookConfig current = read(spec, spec.configPath());
        if (current != null) {
            return current;
        }
        if (new File(spec.configPath()).exists()) {
            return null;
        }
        String legacy = spec.legacyConfigPath();
        return legacy.isEmpty() ? null : read(spec, legacy);
    }

    private static HookConfig read(TargetSpec spec, String path) {
        try (InputStream in = new FileInputStream(path)) {
            byte[] data = new byte[Math.max(1024, in.available())];
            int n = in.read(data);
            if (n <= 0) return null;
            JSONObject o = new JSONObject(new String(data, 0, n, "UTF-8"));
            HookConfig config = new HookConfig(spec.id(), path, o);
            return config.validFor(spec) ? config : null;
        } catch (Throwable t) {
            return null;
        }
    }

    boolean valid() {
        return simOperator.matches("\\d{5,6}")
                && simCountryIso.matches("[A-Za-z]{2}")
                && !simOperatorName.isEmpty()
                && androidId.matches("[0-9a-fA-F]{16}");
    }

    /** True only for a complete, finite point; a half-written payload never fakes a fix. */
    boolean hasLocation() {
        return locationEnabled
                && Double.isFinite(latitude) && latitude >= -90.0d && latitude <= 90.0d
                && Double.isFinite(longitude) && longitude >= -180.0d && longitude <= 180.0d;
    }

    /** A canonical payload must name its target; the legacy single-target payload may omit it. */
    private boolean validFor(TargetSpec spec) {
        return valid() && (target.isEmpty() || target.equals(spec.id()));
    }

    String signature() {
        return Integer.toHexString((targetId + "|" + target + "|" + countryCodeHint + "|"
                + simOperator + "|" + simCountryIso + "|"
                + simOperatorName + "|" + netOperator + "|" + netCountryIso + "|"
                + netOperatorName + "|" + androidId + "|" + locale + "|" + localeList + "|"
                + tz + "|"
                + locationEnabled + "|" + latitude + "|" + longitude + "|"
                + locationCity + "|" + locationSeed + "|" + wifi.size() + "|" + ble.size()
                + "|" + sensorsEnabled)
                .hashCode());
    }

    private static List<String> localeList(String tag, JSONObject object, JSONArray top) {
        List<String> out = new ArrayList<>();
        JSONArray list = top;
        if (list == null && object != null) {
            list = object.optJSONArray("list");
        }
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String value = list.optString(i, "").trim();
                if (!value.isEmpty() && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        if (!tag.isEmpty() && !out.contains(tag)) {
            out.add(0, tag);
        }
        if (!tag.isEmpty() && out.isEmpty()) {
            out.add(tag);
        }
        return out;
    }

    private static float[] floats(JSONObject object, String name) {
        if (object == null) {
            return null;
        }
        JSONArray array = object.optJSONArray(name);
        if (array == null || array.length() == 0) {
            return null;
        }
        float[] out = new float[array.length()];
        for (int i = 0; i < array.length(); i++) {
            out[i] = (float) array.optDouble(i, 0.0d);
        }
        return out;
    }

    private static java.util.List<WifiProfile.Ap> parseWifi(JSONArray array) {
        java.util.List<WifiProfile.Ap> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new WifiProfile.Ap(o.optString("ssid", ""), o.optString("bssid", ""),
                    o.optInt("frequency", 2412), o.optInt("level", -70),
                    o.optString("capabilities", "[ESS]"), o.optBoolean("connected", false),
                    o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN)));
        }
        return out;
    }

    private static java.util.List<BleProfile.Device> parseBle(JSONArray array) {
        java.util.List<BleProfile.Device> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            out.add(new BleProfile.Device(o.optString("name", ""), o.optString("address", ""),
                    o.optInt("rssi", -70), o.optInt("txPower", 0),
                    o.optBoolean("bonded", false)));
        }
        return out;
    }

    private static Map<String, String> map(JSONObject object) {
        Map<String, String> out = new LinkedHashMap<>();
        if (object == null) return out;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, object.optString(key, ""));
        }
        return out;
    }
}
