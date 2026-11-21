package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;

import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.platform.Diag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the static audited Bodyguard allowlist asset ({@code catalog/bodyguard-allowlist.json})
 * and persists per-space Bodyguard mode (default {@link BodyguardPolicy#MODE_OBSERVE}),
 * reversible user whitelist additions, and process lock state.
 */
final class BodyguardStore {
    private static final String ASSET = "catalog/bodyguard-allowlist.json";
    private static final String PREFS = "bodyguard";
    private static final String MODE_PREFIX = "mode.";
    private static final String WHITELIST_PREFIX = "whitelist.";
    private static final String LOCKED_PREFIX = "locked.";

    private static volatile Map<String, BodyguardPolicy.TargetBaseline> cachedBaselines;
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PAYLOAD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    private BodyguardStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** Mode defaults to OBSERVE on first upgrade (per user decision). */
    static String mode(Context ctx, String userId) {
        String u = userId == null || userId.isEmpty() ? "0" : userId;
        return BodyguardPolicy.normalizeMode(
                prefs(ctx).getString(MODE_PREFIX + u, BodyguardPolicy.MODE_OBSERVE));
    }

    static void setMode(Context ctx, String userId, String mode) {
        String u = userId == null || userId.isEmpty() ? "0" : userId;
        prefs(ctx).edit().putString(MODE_PREFIX + u, BodyguardPolicy.normalizeMode(mode)).apply();
        PAYLOAD_CACHE.clear();
    }

    static boolean isLocked(Context ctx, String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            return false;
        }
        return prefs(ctx).getBoolean(LOCKED_PREFIX + targetId, false);
    }

    static void setLocked(Context ctx, String targetId, boolean locked) {
        if (targetId == null || targetId.isEmpty()) {
            return;
        }
        prefs(ctx).edit().putBoolean(LOCKED_PREFIX + targetId, locked).apply();
    }

    /** Adds a user-whitelisted signal for {@code targetId} (reversible via {@link #removeWhitelist}). */
    static void addWhitelist(Context ctx, String targetId, String gate, String signalKey) {
        if (targetId == null || signalKey == null || signalKey.trim().isEmpty()) {
            return;
        }
        String g = (gate == null || gate.trim().isEmpty())
                ? BodyguardPolicy.GATE_TRIPWIRE_API : gate.trim();
        String k = signalKey.trim();
        SharedPreferences p = prefs(ctx);
        Set<String> cur = new LinkedHashSet<String>(
                p.getStringSet(WHITELIST_PREFIX + targetId, Collections.<String>emptySet()));
        cur.add(g + "|" + k);
        p.edit().putStringSet(WHITELIST_PREFIX + targetId, cur).apply();
        PAYLOAD_CACHE.clear();
    }

    /** Removes a previously user-whitelisted signal. */
    static void removeWhitelist(Context ctx, String targetId, String signalKey) {
        if (targetId == null || signalKey == null) {
            return;
        }
        String k = signalKey.trim();
        SharedPreferences p = prefs(ctx);
        Set<String> cur = new LinkedHashSet<String>(
                p.getStringSet(WHITELIST_PREFIX + targetId, Collections.<String>emptySet()));
        Set<String> next = new LinkedHashSet<String>();
        for (String row : cur) {
            int bar = row.indexOf('|');
            String rowKey = bar >= 0 ? row.substring(bar + 1) : row;
            if (!k.equals(rowKey)) {
                next.add(row);
            }
        }
        p.edit().putStringSet(WHITELIST_PREFIX + targetId, next).apply();
        PAYLOAD_CACHE.clear();
    }

    /** Returns custom user-whitelisted entries for {@code targetId}. */
    static List<BodyguardPolicy.SignalEntry> customWhitelist(Context ctx, String targetId,
                                                             String versionLabel) {
        List<BodyguardPolicy.SignalEntry> out = new ArrayList<BodyguardPolicy.SignalEntry>();
        if (targetId == null || targetId.isEmpty()) {
            return out;
        }
        Set<String> raw = prefs(ctx).getStringSet(
                WHITELIST_PREFIX + targetId, Collections.<String>emptySet());
        for (String row : raw) {
            int bar = row.indexOf('|');
            String gate = bar > 0 ? row.substring(0, bar) : BodyguardPolicy.GATE_TRIPWIRE_API;
            String key = bar >= 0 ? row.substring(bar + 1) : row;
            if (!key.isEmpty()) {
                out.add(new BodyguardPolicy.SignalEntry(
                        key, gate, BodyguardPolicy.STATUS_CUSTOM, "user",
                        versionLabel == null ? "" : versionLabel, true));
            }
        }
        return out;
    }

    /** Returns combined custom whitelist (first) + static audited baseline for {@code targetId}. */
    static BodyguardPolicy.TargetBaseline baselineWithCustom(Context ctx, String targetId) {
        BodyguardPolicy.TargetBaseline base = staticBaseline(ctx, targetId);
        String ver = base.versionCode > 0 ? String.valueOf(base.versionCode) : base.versionName;
        List<BodyguardPolicy.SignalEntry> custom = customWhitelist(ctx, targetId, ver);
        if (custom.isEmpty()) {
            return base;
        }
        List<BodyguardPolicy.SignalEntry> merged =
                new ArrayList<BodyguardPolicy.SignalEntry>(custom.size() + base.entries.size());
        merged.addAll(custom);
        merged.addAll(base.entries);
        return new BodyguardPolicy.TargetBaseline(
                base.targetId, base.versionCode, base.versionName, merged);
    }

    /**
     * Serializes the active Bodyguard mode + static baseline + reversible user whitelist into a
     * compact JSON object embedded in the per-target {@code HookConfig} file.
     */
    static String payloadJson(Context ctx, String targetId, String userId) {
        String key = (targetId == null ? "" : targetId) + "|" + (userId == null ? "0" : userId);
        String cached = PAYLOAD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            String activeMode = mode(ctx, userId);
            BodyguardPolicy.TargetBaseline base = baselineWithCustom(ctx, targetId);
            JSONObject obj = new JSONObject();
            obj.put("mode", activeMode);
            obj.put("version", base.versionCode > 0 ? String.valueOf(base.versionCode) : base.versionName);

            JSONArray telemetry = new JSONArray();
            JSONArray props = new JSONArray();
            JSONArray settings = new JSONArray();
            JSONArray apis = new JSONArray();
            JSONArray paths = new JSONArray();
            JSONArray hosts = new JSONArray();
            JSONArray custom = new JSONArray();

            for (BodyguardPolicy.SignalEntry e : base.entries) {
                if (BodyguardPolicy.STATUS_TRIPWIRE.equals(e.status) && !e.custom) {
                    continue;
                }
                if (e.custom) {
                    custom.put(e.key);
                }
                String k = e.key;
                if (BodyguardPolicy.GATE_TELEMETRY_KEY.equals(e.gate)) {
                    telemetry.put(k.startsWith("meta:") ? k.substring(5) : k);
                } else if (BodyguardPolicy.GATE_SYS_PROP.equals(e.gate)) {
                    props.put(k.startsWith("prop:") ? k.substring(5) : k);
                } else if (BodyguardPolicy.GATE_SETTINGS_KEY.equals(e.gate)) {
                    settings.put(k.startsWith("settings:") ? k.substring(9) : k);
                } else if (BodyguardPolicy.GATE_SENSITIVE_PATH.equals(e.gate)) {
                    paths.put(k.startsWith("path:") ? k.substring(5) : k);
                } else if (BodyguardPolicy.GATE_EGRESS_HOST.equals(e.gate)) {
                    hosts.put(k.startsWith("host:") ? k.substring(5) : k);
                } else {
                    apis.put(k.startsWith("api:") ? k.substring(4) : k);
                }
            }
            obj.put("allowTelemetry", telemetry);
            obj.put("allowProps", props);
            obj.put("allowSettings", settings);
            obj.put("allowApis", apis);
            obj.put("allowPaths", paths);
            obj.put("allowHosts", hosts);
            obj.put("allowCustom", custom);
            String json = obj.toString();
            PAYLOAD_CACHE.put(key, json);
            return json;
        } catch (Throwable t) {
            Diag.warn("bodyguard", "payloadJson failed", t);
            return "{\"mode\":\"OFF\"}";
        }
    }

    static BodyguardPolicy.TargetBaseline staticBaseline(Context ctx, String targetId) {
        Map<String, BodyguardPolicy.TargetBaseline> map = loadAll(ctx);
        BodyguardPolicy.TargetBaseline found = targetId == null ? null : map.get(targetId);
        if (found != null) {
            return found;
        }
        return new BodyguardPolicy.TargetBaseline(
                targetId == null ? "" : targetId, 0L, "",
                Collections.<BodyguardPolicy.SignalEntry>emptyList());
    }

    private static Map<String, BodyguardPolicy.TargetBaseline> loadAll(Context ctx) {
        Map<String, BodyguardPolicy.TargetBaseline> snap = cachedBaselines;
        if (snap != null) {
            return snap;
        }
        synchronized (BodyguardStore.class) {
            if (cachedBaselines != null) {
                return cachedBaselines;
            }
            Map<String, BodyguardPolicy.TargetBaseline> parsed =
                    new LinkedHashMap<String, BodyguardPolicy.TargetBaseline>();
            try (InputStream in = ctx.getApplicationContext().getAssets().open(ASSET)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONObject targets = root.optJSONObject("targets");
                if (targets != null) {
                    java.util.Iterator<String> keys = targets.keys();
                    while (keys.hasNext()) {
                        String tid = keys.next();
                        JSONObject t = targets.optJSONObject(tid);
                        if (t == null) {
                            continue;
                        }
                        long vc = t.optLong("version_code", 0L);
                        String vn = t.optString("version_name", "");
                        String verLabel = vc > 0 ? String.valueOf(vc) : vn;
                        JSONArray arr = t.optJSONArray("signals");
                        List<BodyguardPolicy.SignalEntry> entries =
                                new ArrayList<BodyguardPolicy.SignalEntry>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONArray row = arr.optJSONArray(i);
                                if (row == null || row.length() < 4) {
                                    continue;
                                }
                                entries.add(new BodyguardPolicy.SignalEntry(
                                        row.optString(0, ""),
                                        row.optString(1, BodyguardPolicy.GATE_TRIPWIRE_API),
                                        row.optString(2, BodyguardPolicy.STATUS_EXEMPT),
                                        row.optString(3, ""),
                                        verLabel,
                                        false));
                            }
                        }
                        parsed.put(tid, new BodyguardPolicy.TargetBaseline(tid, vc, vn, entries));
                    }
                }
            } catch (Throwable t) {
                Diag.warn("bodyguard", "cannot load " + ASSET, t);
            }
            cachedBaselines = Collections.unmodifiableMap(parsed);
            return cachedBaselines;
        }
    }
}
