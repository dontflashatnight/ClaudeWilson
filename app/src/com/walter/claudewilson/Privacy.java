package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** One UI-only switch for machine-sensitive values; the model and writes are not changed. */
final class Privacy {
    static final String STARS = "********";

    private static final String PREFS = "privacy";
    private static final String KEY = "hidden";

    private static volatile boolean hidden;
    private static SharedPreferences preferences;

    private Privacy() {
    }

    static void init(Context context) {
        preferences = context.getSharedPreferences(PREFS, 0);
        hidden = preferences.getBoolean(KEY, false);
    }

    static boolean hidden() {
        return hidden;
    }

    static void toggle(Context context) {
        hidden = !hidden;
        if (preferences == null) {
            preferences = context.getSharedPreferences(PREFS, 0);
        }
        preferences.edit().putBoolean(KEY, hidden).apply();
    }

    static String mask(String value) {
        if (!hidden || value == null || value.isEmpty()
                || "—".equals(value) || "?".equals(value)) {
            return value;
        }
        return STARS;
    }

    /** Explicit list: identifiers, fingerprints and SIM/account values, not every field. */
    static boolean sensitiveId(String id) {
        if (id == null) {
            return false;
        }
        switch (id) {
            case "device.id":
            case "device.uuid":
            case "device.seeded":
            case "sdk.growthbook.unit":
            case "sdk.segment.anonymous":
            case "sdk.segment.user":
            case "sdk.segment.device":
            case "sdk.sift.installation":
            case "sdk.sift.fingerprint":
            case "sdk.sentry.installation":
            case "sdk.sentry.device_tag":
            case "sdk.datadog.anonymous":
            case "sdk.statsig.stable":
            case "sdk.firebase.fid":
            case "account.local_handle":
            case "account.organization":
            case "integrity.play_prefix":
            case "transport.datatransport.fingerprint":
            case "transport.datatransport.build_parts":
            case "target.aid":
            case "target.build":
            case "target.build_parts":
            case "target.simop":
            case "target.simiso":
            case "target.carrier":
            case "system.android_id":
            case "system.build":
            case "system.sim_operator":
            case "system.sim_iso":
            case "system.carrier":
            case "system.sales_code":
                return true;
            default:
                return false;
        }
    }

    /** Persona row labels are localized; cover both Chinese and English forms. */
    static boolean sensitivePersona(String label) {
        if (label == null) {
            return false;
        }
        String text = label.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        return text.startsWith("SIM ")
                || "AID".equals(text)
                || text.contains("机型")
                || text.contains("指纹")
                || text.contains("拜访网络")
                || text.contains("区域标记")
                || lower.contains("fingerprint")
                || lower.contains("visited")
                || lower.contains("region")
                || lower.contains("model");
    }
}
