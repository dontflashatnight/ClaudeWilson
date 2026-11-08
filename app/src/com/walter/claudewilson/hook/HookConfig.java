package com.walter.claudewilson.hook;

import com.walter.claudewilson.model.TargetSpec;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
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
    final String tz;
    final boolean hideAccounts;
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
        locale = o.optString("locale", "");
        tz = o.optString("tz", "");
        hideAccounts = o.optBoolean("hideAccounts", false);
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

    /** A canonical payload must name its target; the legacy single-target payload may omit it. */
    private boolean validFor(TargetSpec spec) {
        return valid() && (target.isEmpty() || target.equals(spec.id()));
    }

    String signature() {
        return Integer.toHexString((targetId + "|" + target + "|" + countryCodeHint + "|"
                + simOperator + "|" + simCountryIso + "|"
                + simOperatorName + "|" + netOperator + "|" + netCountryIso + "|"
                + netOperatorName + "|" + androidId + "|" + locale + "|" + tz)
                .hashCode());
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
