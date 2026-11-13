package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * One transaction state for the region layer.
 *
 * <p>Baseline is captured once and is the only target for "off". Apply and revert are tokenized,
 * and the shell work itself is serialized by Dashboard's transaction lock, so a stale apply
 * cannot publish state after an off tap.
 */
final class EnvSession {
    private static final String PREFS = "apply";
    private static final String PHASE = "env_phase";
    private static final String TOKEN = "env_token";

    static final String OFF = "OFF";
    static final String APPLYING = "APPLYING";
    static final String ACTIVE = "ACTIVE";
    static final String REVERTING = "REVERTING";
    static final String PARTIAL = "PARTIAL";

    private EnvSession() {}

    static final class Baseline {
        final String tz;
        final int autoTz;
        final long at;
        final String simNum, simIso, simAlpha, simDisplay;
        final int simSource;
        final int sub;
        final String target, user;
        final String targetLocale, browserLocale;
        Baseline(String tz, int autoTz, long at, String simNum, String simIso,
                 String simAlpha, String simDisplay, int simSource, int sub,
                 String target, String user, String targetLocale, String browserLocale) {
            this.tz = tz; this.autoTz = autoTz; this.at = at;
            this.simNum = simNum; this.simIso = simIso; this.simAlpha = simAlpha;
            this.simDisplay = simDisplay; this.simSource = simSource;
            this.sub = sub; this.target = target; this.user = user;
            this.targetLocale = targetLocale; this.browserLocale = browserLocale;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    /** Move the legacy orig_ and sel_ keys once; afterwards EnvSession is the only truth. */
    private static void migrate(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean("env_migrated", false)) {
            return;
        }
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("base_tz") && p.contains("orig_tz")) {
            e.putString("base_tz", p.getString("orig_tz", null))
                    .putInt("base_autotz", p.getInt("orig_autotz", 1))
                    .putLong("base_at", p.getLong("orig_tz_at", 0L))
                    .putString("base_sim_num", p.getString("orig_sim_num", ""))
                    .putString("base_sim_iso", p.getString("orig_sim_iso", ""))
                    .putString("base_sim_alpha", p.getString("orig_sim_alpha", ""))
                    .putInt("base_sub", p.getInt("applied_sub", -1))
                    .putString("base_target", p.getString("applied_target", null))
                    .putString("base_user", p.getString("orig_user", null));
        }
        if (!p.contains("act_iso") && p.contains("sel_iso")) {
            e.putString("act_iso", p.getString("sel_iso", null))
                    .putString("act_zone", p.getString("sel_zone", null))
                    .putString("act_locale", p.getString("sel_locale", null))
                    .putBoolean("act_applied", p.getBoolean("sel_applied", false));
        }
        if (!p.contains(PHASE)) {
            e.putString(PHASE, p.getBoolean("sel_applied", false) ? ACTIVE : OFF);
        }
        e.putBoolean("env_migrated", true).commit();
    }

    static String phase(Context context) {
        migrate(context);
        return prefs(context).getString(PHASE, OFF);
    }

    static long token(Context context) {
        migrate(context);
        return prefs(context).getLong(TOKEN, 0L);
    }

    static boolean hasBaseline(Context context) {
        migrate(context);
        return prefs(context).contains("base_tz");
    }

    static Baseline baseline(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        if (!p.contains("base_tz")) {
            return null;
        }
        return new Baseline(p.getString("base_tz", null), p.getInt("base_autotz", 1),
                p.getLong("base_at", 0L), p.getString("base_sim_num", ""),
                p.getString("base_sim_iso", ""), p.getString("base_sim_alpha", ""),
                p.getString("base_sim_display", ""), p.getInt("base_sim_source", -1),
                p.getInt("base_sub", -1), p.getString("base_target", null),
                p.getString("base_user", null), p.getString("base_target_locale", ""),
                p.getString("base_browser_locale", ""));
    }

    /**
     * Captures the first baseline. A different subscription replaces the SIM baseline, matching
     * the old snapshot rule, while an ordinary re-apply keeps the original baseline.
     */
    static void captureBaseline(Context context, String simNum, String simIso,
                                String simAlpha, String simDisplay, int simSource,
                                int sub, String target, String user,
                                String targetLocale, String browserLocale) {
        migrate(context);
        SharedPreferences p = prefs(context);
        boolean fresh = !p.contains("base_tz");
        int oldSub = p.getInt("base_sub", -1);
        if (!fresh && oldSub == sub) {
            return;
        }
        SharedPreferences.Editor e = p.edit()
                .putString("base_sim_num", simNum == null ? "" : simNum)
                .putString("base_sim_iso", simIso == null ? "" : simIso)
                .putString("base_sim_alpha", simAlpha == null ? "" : simAlpha)
                .putString("base_sim_display", simDisplay == null ? "" : simDisplay)
                .putInt("base_sim_source", simSource)
                .putInt("base_sub", sub)
                .putString("base_target", target)
                .putString("base_user", user)
                .putLong("base_at", System.currentTimeMillis());
        if (fresh) {
            // Read the system property, not this process's cached TimeZone. A process started
            // before a previous test/manual change keeps the old zone in TimeZone.getDefault().
            String tz = DeviceSignals.getprop("persist.sys.timezone");
            if (tz == null || tz.isEmpty()) {
                tz = java.util.TimeZone.getDefault().getID();
            }
            e.putString("base_tz", tz)
                    .putInt("base_autotz", Settings.Global.getInt(
                            context.getContentResolver(), "auto_time_zone", 1))
                    .putString("base_target_locale", targetLocale == null ? "" : targetLocale)
                    .putString("base_browser_locale", browserLocale == null ? "" : browserLocale);
        }
        e.commit();
    }

    static String activeIso(Context context) {
        migrate(context);
        return prefs(context).getString("act_iso", null);
    }

    static String activeZone(Context context) {
        migrate(context);
        return prefs(context).getString("act_zone", null);
    }

    static String activeLocale(Context context) {
        migrate(context);
        return prefs(context).getString("act_locale", null);
    }

    static boolean activeApplied(Context context) {
        migrate(context);
        return prefs(context).getBoolean("act_applied", false);
    }

    static void setActive(Context context, String iso, String zone, String locale,
                          boolean applied) {
        migrate(context);
        prefs(context).edit().putString("act_iso", iso).putString("act_zone", zone)
                .putString("act_locale", locale).putBoolean("act_applied", applied).commit();
    }

    static void setActiveZone(Context context, String zone) {
        migrate(context);
        prefs(context).edit().putString("act_zone", zone).commit();
    }

    static void clearActive(Context context) {
        migrate(context);
        prefs(context).edit().remove("act_iso").remove("act_zone").remove("act_locale")
                .remove("act_applied").commit();
    }

    /** Starts an apply and invalidates every older completion. */
    static long startApply(Context context, String iso, String zone, String locale) {
        migrate(context);
        SharedPreferences p = prefs(context);
        long token = p.getLong(TOKEN, 0L) + 1L;
        p.edit().putLong(TOKEN, token).putString(PHASE, APPLYING)
                .putString("act_iso", iso).putString("act_zone", zone)
                .putString("act_locale", locale).putBoolean("act_applied", false).commit();
        return token;
    }

    /** Starts a revert and invalidates any apply still finishing. */
    static long startRevert(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        long token = p.getLong(TOKEN, 0L) + 1L;
        p.edit().putLong(TOKEN, token).putString(PHASE, REVERTING).commit();
        return token;
    }

    static boolean isCurrent(Context context, long token) {
        return token(context) == token;
    }

    static void finishApply(Context context, long token, boolean ok) {
        if (!isCurrent(context, token)) {
            return;
        }
        prefs(context).edit().putString(PHASE, ok ? ACTIVE : PARTIAL)
                .putBoolean("act_applied", ok).commit();
    }

    static void finishRevert(Context context, long token, boolean ok) {
        if (!isCurrent(context, token)) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(PHASE, ok ? OFF : PARTIAL);
        if (ok) {
            // The next region starts from whatever the device looks like then, not from a
            // snapshot that belongs to the previous session.
            editor.remove("base_tz").remove("base_autotz").remove("base_at")
                    .remove("base_sim_num").remove("base_sim_iso").remove("base_sim_alpha")
                    .remove("base_sim_display").remove("base_sim_source")
                    .remove("base_sub").remove("base_target").remove("base_user")
                    .remove("act_iso").remove("act_zone").remove("act_locale")
                    .remove("act_applied");
        }
        editor.commit();
    }
}
