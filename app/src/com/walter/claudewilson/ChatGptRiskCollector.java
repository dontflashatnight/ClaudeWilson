package com.walter.claudewilson;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import com.walter.claudewilson.model.ChatGptChecklist;
import com.walter.claudewilson.model.RiskModel;
import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.platform.Diag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ChatGPT check items from the target's own data, its self-check and the live system.
 *
 * <p>This is the target-side counterpart of the old name-level inventory: it reads values only
 * for the fixed paths named by the analysis, strips tokens before they leave the root shell, and
 * never grades the control app's own root/mount view as a target fact.
 *
 * <p>One root command collects the target files. The SQLite queues are scanned with
 * {@code grep -a} for a closed set of JSON keys because the device has no {@code sqlite3}
 * binary; the model marks a key unreadable when that scan produces nothing.
 */
final class ChatGptRiskCollector {
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX32 = Pattern.compile("[0-9a-fA-F]{32}");

    RiskModel collect(Context context, TargetSpec spec, Snapshot snapshot, String playCountry) {
        if (spec == null || !"chatgpt".equals(spec.id())) {
            return RiskModel.EMPTY;
        }
        Map<String, String> data = new LinkedHashMap<String, String>();
        Map<String, String> hook = new LinkedHashMap<String, String>();
        boolean root = RootShell.rootReady();
        boolean privileged = RootShell.shellReady();
        boolean running = false;
        boolean live = false;
        if (root && ShellQuote.packageName(spec.packageName())) {
            String dump = RootShell.run(script(spec.packageName(), spec.id()), 65536, 25000L);
            if (dump == null || dump.startsWith("exc:") || dump.startsWith("timeout:")
                    || !dump.contains("@@device_uuid")) {
                Diag.warn("risk", "target file read failed for " + spec.packageName(), null);
            } else {
                Map<String, String> raw = sections(dump);
                parse(raw, data);
                applyTelemetry(context, spec, raw, data);
                put(data, "sim_carrier_id", carrierId(context));
                hook = parseHook(raw.get("hookcheck"));
            }
        }
        if (!root && privileged && ShellQuote.packageName(spec.packageName())) {
            String payload = RootShell.run(
                    "cat /data/local/tmp/claudewilson/" + spec.id() + ".json 2>/dev/null", 8192);
            if (payload.contains("\"target\"")) {
                Map<String, String> preview = new LinkedHashMap<String, String>();
                preview.put("payload", payload);
                parse(preview, data);
            }
            String external = RootShell.run("cat /storage/emulated/0/Android/data/"
                    + spec.packageName() + "/files/cw-hookcheck.txt 2>/dev/null", 8192);
            if (external.contains("pid=")) {
                hook = parseHook(external);
            }
        }
        if (privileged && ShellQuote.packageName(spec.packageName())) {
            String localeOut = RootShell.run(
                    "cmd locale get-app-locales " + spec.packageName(), 256);
            put(data, "app_locale", firstLocale(localeOut));
            String pidOut = RootShell.run("pidof " + spec.packageName(), 64).trim();
            running = pidOut.matches("\\d+(\\s+\\d+)*");
            String pid = running ? pidOut.split("\\s+")[0] : "";
            live = running && pid.equals(hook.get("pid"));
        }
        data.put("target_state", !privileged ? "unknown" : running ? "running" : "stopped");
        put(data, "target_app", appVersion(context, spec.packageName()));
        put(data, "build_parts", buildParts());
        String timezone = TimeZone.getDefault().getID();
        long now = System.currentTimeMillis() / 1000L;
        return ChatGptChecklist.build(data, hook, snapshot, timezone, playCountry, now, live);
    }

    private static void parse(Map<String, String> raw, Map<String, String> out) {
        put(out, "device_uuid", json(raw.get("device_uuid"), "d"));
        put(out, "block_seed", json(raw.get("app_settings"),
                "block_store_seeded_device_id"));

        String prefs = raw.get("segment_prefs");
        put(out, "segment_anonymous", xml(prefs, "segment.anonymousId"));
        put(out, "segment_user", xml(prefs, "segment.userId"));
        put(out, "segment_device", xml(prefs, "segment.device.id"));

        String statsig = raw.get("statsig");
        put(out, "statsig_stable", latestStatsig(statsig, "stableID"));
        put(out, "statsig_locale", latestStatsig(statsig, "locale"));

        Map<String, String> firebase = keyPrefix(raw.get("firebase"));
        if (firebase != null) {
            put(out, "firebase_country", firebase.get("country"));
            put(out, "firebase_locale", firebase.get("locale"));
            put(out, "firebase_mcc", firebase.get("mcc_mnc"));
            put(out, "firebase_tz", firebase.get("tz-offset"));
            put(out, "firebase_fingerprint", firebase.get("fingerprint"));
        }

        put(out, "sentry_installation", firstId(raw.get("sentry_installation")));
        put(out, "sentry_native", firstId(raw.get("sentry_native")));
        put(out, "sentry_device", json(raw.get("sentry_tags"), "deviceId"));
        put(out, "account_handle", json(raw.get("sentry_user"), "id"));
        put(out, "datadog_anonymous", firstId(raw.get("datadog_anonymous")));
        String datadogRum = raw.get("datadog_rum");
        if (datadogRum != null) {
            Matcher dd = Pattern.compile(
                            "\"play_store_country\"\\s*:\\s*\"([A-Za-z]{2})\"")
                    .matcher(datadogRum);
            if (dd.find()) {
                put(out, "datadog_play_country", dd.group(1).toUpperCase(Locale.ROOT));
            }
        }
        put(out, "firebase_fid", json(raw.get("firebase_fid"), "Fid"));
        put(out, "country_hint", json(raw.get("country_hint"), "country_code_hint"));
        String payload = raw.get("payload");
        put(out, "payload_cc_hint", json(payload, "countryCodeHint"));
        put(out, "payload_locale", json(payload, "locale"));
        put(out, "payload_tz", json(payload, "tz"));
        put(out, "payload_simop", json(payload, "simOperator"));
        put(out, "payload_simiso", json(payload, "simCountryIso"));
        put(out, "payload_simname", json(payload, "simOperatorName"));
        put(out, "payload_aid", json(payload, "androidId"));
        String model = json(payload, "MODEL");
        String fingerprint = json(payload, "FINGERPRINT");
        if (model != null && fingerprint != null) {
            put(out, "payload_build", model + "|" + fingerprint);
        }
        put(out, "payload_build_parts", buildParts(
                json(payload, "MANUFACTURER"), json(payload, "DEVICE"),
                json(payload, "PRODUCT"), json(payload, "HARDWARE"), model));

        String account = raw.get("statsig_account");
        String days = lastScalar(account, "days_since_user_creation");
        if (days != null) {
            put(out, "account_age", days + "d");
        }
        String plan = lastJson(account, "plan_type");
        String subscription = lastJson(account, "subscription_plan");
        String active = lastScalar(account, "has_active_subscription");
        String had = lastScalar(account, "has_had_subscription");
        if (plan != null || subscription != null || active != null || had != null) {
            put(out, "account_subscription", "plan=" + nz(plan) + ",sub=" + nz(subscription)
                    + ",active=" + nz(active) + ",had=" + nz(had));
        }

        String ray = raw.get("cf_ray");
        if (ray != null) {
            Matcher matcher = Pattern.compile("cf_ray_id[^A-Za-z0-9-]*([A-Za-z0-9-]+)")
                    .matcher(ray);
            if (matcher.find()) {
                put(out, "cf_ray", matcher.group(1));
            }
        }

        String integrity = raw.get("playintegrity");
        if (integrity != null) {
            String value = integrity.trim();
            int colon = value.indexOf(':');
            if (colon > 0) {
                put(out, "playintegrity_prefix", value.substring(0, colon).trim());
                String stamp = value.substring(colon + 1).trim();
                int dash = stamp.indexOf('-');
                if (dash > 0) {
                    stamp = stamp.substring(0, dash);
                }
                put(out, "playintegrity_ts", stamp);
            }
        }

        Map<String, String> counts = counts(raw.get("cookies"));
        if (counts != null) {
            put(out, "cfuvid", count(counts, "_cfuvid="));
            put(out, "cflb", count(counts, "__cflb="));
            put(out, "oailb", count(counts, "__oailb="));
            put(out, "oai_sc", count(counts, "oai-sc="));
            put(out, "conv_key", count(counts, "conv_key_"));
        }
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static String script(String pkg, String id) {
        StringBuilder s = new StringBuilder();
        s.append("P=/proc/1/root/data/data/").append(pkg)
                .append("; [ -d \"$P\" ] || P=/data/data/").append(pkg).append(";\n");
        section(s, "device_uuid",
                "head -c 1024 \"$P/files/datastore/global_device_uuid.json\" 2>/dev/null");
        section(s, "app_settings",
                "head -c 2048 \"$P/files/datastore/global_app_settings.json\" 2>/dev/null");
        section(s, "segment_prefs",
                "head -c 4096 \"$P/shared_prefs/analytics-android-oai.xml\" 2>/dev/null");
        section(s, "sentry_installation",
                "head -c 128 \"$P/files/INSTALLATION\" 2>/dev/null");
        section(s, "sentry_native",
                "find \"$P/cache/sentry\" -maxdepth 3 -name installation_id "
                        + "-exec cat {} \\; 2>/dev/null | head -2");
        section(s, "datadog_anonymous",
                "find \"$P/cache\" -path '*datastore_v0/rum/anonymous_id_key' "
                        + "-exec cat {} \\; 2>/dev/null | head -1");
        section(s, "datadog_rum",
                "for F in \"$P\"/cache/datadog-*/logs-v2/*; do "
                        + "[ -f \"$F\" ] || continue; "
                        + "grep -a -o -E '\"play_store_country\"[[:space:]]*:"
                        + "[[:space:]]*\"[A-Za-z]{2}\"' \"$F\" | head -1; "
                        + "done | head -1");
        section(s, "firebase_fid",
                "F=$(ls \"$P\"/files/PersistedInstallation.*.json 2>/dev/null | head -1); "
                        + "[ -n \"$F\" ] && grep -a -o '\"Fid\":\"[^\"]*\"' \"$F\" | head -1");
        section(s, "statsig",
                "DB=$(ls \"$P\"/no_backup/statsig_event_queue_*.db 2>/dev/null | head -1); "
                        + "[ -n \"$DB\" ] && strings \"$DB\" | grep -a -E "
                        + "'\"(stableID|locale|language|appVersion|deviceModel"
                        + "|deviceManufacturer|time)\"' | tail -80");
        section(s, "firebase",
                "DB=\"$P/databases/com.google.android.datatransport.events\"; "
                        + "[ -f \"$DB\" ] && strings \"$DB\" | grep -a -E "
                        + "'^(country|locale|mcc_mnc|tz-offset|fingerprint|net-type"
                        + "|mobile-subtype|os-build|os-uild|manufacturer|model|device"
                        + "|product|hardware|sdk-version|application_build)' | tail -80");
        section(s, "journal",
                "F=\"$P/cache/cw-telemetry/events.jsonl\"; "
                        + "[ -f \"$F\" ] && cat \"$F\" 2>/dev/null");
        section(s, "statsig_account",
                "DB=$(ls \"$P\"/no_backup/statsig_event_queue_*.db 2>/dev/null | head -1); "
                        + "[ -n \"$DB\" ] && grep -a -o -E "
                        + "'\"(days_since_user_creation|user_create_timestamp"
                        + "|has_had_subscription|has_active_subscription|plan_type"
                        + "|subscription_plan|is_plus_user)\":(\"[^\"]*\"|[0-9]+|true|false)' "
                        + "\"$DB\" | tail -40");
        section(s, "cf_ray",
                "for F in \"$P\"/app_segment-disk-queue/* \"$P\"/cache/datadog-*/logs-v2/*; "
                        + "do [ -f \"$F\" ] || continue; "
                        + "grep -a -o -E 'cf_ray_id[^,}]*' \"$F\" | head -1; done | head -1");
        section(s, "sentry_tags",
                "find \"$P/cache/sentry\" -maxdepth 3 -name tags.json "
                        + "-exec grep -a -o -E '(\"deviceId\"|\"planType\"|\"workspaceId\""
                        + "|\"apiEnvironment\"):\"[^\"]*\"' {} \\; 2>/dev/null | head -20");
        section(s, "sentry_user",
                "find \"$P/cache/sentry\" -maxdepth 3 -name user.json "
                        + "-exec head -c 512 {} \\; 2>/dev/null | head -1");
        section(s, "country_hint",
                "grep -a -o '\"country_code_hint\":\"[^\"]*\"' "
                        + "\"$P/files/datastore/global_phone_country_codes.json\" "
                        + "2>/dev/null | head -1");
        section(s, "playintegrity",
                "grep -a -o '_playintegrity=[^;]*' "
                        + "\"$P/files/network/shared-cookies/global_cookie_storage.json\" "
                        + "2>/dev/null | head -1 | sed 's/^_playintegrity=//' "
                        + "| cut -d: -f1,2");
        section(s, "cookies",
                "F=\"$P/files/network/shared-cookies/global_cookie_storage.json\"; "
                        + "if [ -f \"$F\" ]; then for N in _cfuvid= __cflb= __oailb= "
                        + "oai-sc= conv_key_; do C=$(grep -a -o \"$N\" \"$F\" | wc -l); "
                        + "echo \"$C $N\"; done; fi");
        section(s, "hookcheck",
                "cat \"$P/files/hookcheck.txt\" 2>/dev/null; "
                        + "for F in \"$P\"/files/hookcheck.*.txt; do "
                        + "[ -f \"$F\" ] || continue; "
                        + "echo \"process_file=$(basename \"$F\")\"; cat \"$F\"; done");
        section(s, "payload",
                "F=/data/local/tmp/claudewilson/" + id + ".json; "
                        + "[ -f \"$F\" ] && head -c 8192 \"$F\"");
        return s.toString();
    }

    private static void section(StringBuilder out, String name, String command) {
        out.append("printf '@@").append(name).append("\\n'; ")
                .append(command).append("; printf '\\n';\n");
    }

    private static Map<String, String> sections(String dump) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : dump.split("\n", -1)) {
            if (line.startsWith("@@")) {
                if (current != null) {
                    out.put(current, buffer.toString());
                }
                current = line.substring(2).trim();
                buffer = new StringBuilder();
            } else if (current != null) {
                buffer.append(line).append('\n');
            }
        }
        if (current != null) {
            out.put(current, buffer.toString());
        }
        return out;
    }

    private static Map<String, String> parseHook(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static Map<String, String> counts(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        Pattern line = Pattern.compile("^\\s*(\\d+)\\s+(\\S+)\\s*$");
        for (String row : text.split("\\n")) {
            Matcher matcher = line.matcher(row);
            if (matcher.matches()) {
                out.put(matcher.group(2), matcher.group(1));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Same persistent merge as Claude: the Firebase datatransport queue is not historical. */
    private static void applyTelemetry(Context context, TargetSpec spec,
            Map<String, String> raw, Map<String, String> data) {
        String target = spec.id();
        String live = raw.get("firebase");
        boolean readable = live == null
                || (!live.startsWith("exc:") && !live.startsWith("timeout:"));
        Map<String, String> firebase = keyPrefix(live);
        TelemetryStore.ingest(context, target, firebase, "datatransport");
        TelemetryStore.ingestJournal(context, target, raw.get("journal"));
        TelemetryStore.ingestDatabase(context, target, spec.packageName());
        Map<String, TelemetryStore.Entry> stored = TelemetryStore.read(context, target);
        fill(data, stored, "firebase_country", "country");
        fill(data, stored, "firebase_locale", "locale");
        fill(data, stored, "firebase_mcc", "mcc_mnc");
        fill(data, stored, "firebase_tz", "tz-offset");
        fill(data, stored, "firebase_fingerprint", "fingerprint");
        data.put("firebase_readable", readable ? "1" : "0");

        Map<String, String> extra = new LinkedHashMap<String, String>();
        Map<String, String> hook = parseHook(raw.get("hookcheck"));
        for (Map.Entry<String, String> item : hook.entrySet()) {
            extra.put("hook." + item.getKey(), item.getValue());
        }
        TelemetryStore.ingest(context, target, extra, "snapshot");
    }

    private static void fill(Map<String, String> data,
            Map<String, TelemetryStore.Entry> stored, String outKey, String storeKey) {
        String value = TelemetryStore.latest(stored, storeKey);
        if (value != null && !value.isEmpty()) {
            put(data, outKey, value);
        }
    }

    /** Firebase transport stores the metadata as protobuf; strings leaves key+value pairs. */
    private static Map<String, String> keyPrefix(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String[] keys = {"country", "locale", "mcc_mnc", "tz-offset", "fingerprint",
                "net-type", "mobile-subtype", "os-build", "os-uild",
                "manufacturer", "model", "device", "product", "hardware",
                "sdk-version", "application_build"};
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : text.split("\\n")) {
            for (String key : keys) {
                if (line.startsWith(key) && line.length() > key.length()) {
                    String canonical = "os-uild".equals(key) ? "os-build" : key;
                    out.put(canonical, line.substring(key.length()).trim());
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String count(Map<String, String> counts, String key) {
        String value = counts.get(key);
        return value == null ? "0" : value;
    }

    private static String json(String text, String key) {
        return json(text, key, false);
    }

    private static String lastJson(String text, String key) {
        return json(text, key, true);
    }

    /** Statsig stores many queued events; the one with the largest time is the current value. */
    private static String latestStatsig(String text, String key) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String best = null;
        long bestTime = Long.MIN_VALUE;
        for (String line : text.split("\\n")) {
            String value = json(line, key, true);
            if (value == null) {
                continue;
            }
            java.util.regex.Matcher time = java.util.regex.Pattern
                    .compile("\"time\"\\s*:\\s*(\\d{10,})").matcher(line);
            long at = time.find() ? Long.parseLong(time.group(1)) : Long.MIN_VALUE;
            if (at >= bestTime) {
                best = value;
                bestTime = at;
            }
        }
        return best;
    }

    /** Last scalar (number/bool/quoted string) for keys the Statsig DB stores unquoted. */
    private static String lastScalar(String text, String key) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(\"[^\"]*\"|[^,}\\s]+)");
        Matcher matcher = pattern.matcher(text);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if ("null".equals(value)) {
                value = null;
            }
        }
        return value;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String appVersion(Context context, String pkg) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            String name = info.versionName == null ? "" : info.versionName;
            return name + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstLocale(String output) {
        if (output == null) {
            return null;
        }
        int open = output.indexOf('[');
        int close = output.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return null;
        }
        String value = output.substring(open + 1, close).trim();
        if (value.isEmpty()) {
            return null;
        }
        int comma = value.indexOf(',');
        return comma > 0 ? value.substring(0, comma).trim() : value;
    }

    private static String buildParts() {
        return Build.MANUFACTURER + "|" + Build.DEVICE + "|" + Build.PRODUCT + "|"
                + Build.HARDWARE + "|" + Build.MODEL;
    }

    private static String buildParts(String manufacturer, String device, String product,
                                     String hardware, String model) {
        if (manufacturer == null || device == null || product == null
                || hardware == null || model == null) {
            return null;
        }
        return manufacturer + "|" + device + "|" + product + "|" + hardware + "|" + model;
    }

    private static String json(String text, String key, boolean last) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(text);
        String value = null;
        while (matcher.find()) {
            value = unescape(matcher.group(1));
            if (!last) {
                return value;
            }
        }
        return value;
    }

    private static String xml(String text, String name) {
        if (text == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("name=\"" + Pattern.quote(name)
                + "\"[^>]*>([^<]*)<");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    private static String unescape(String value) {
        return value.replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String firstId(String text) {
        if (text == null) {
            return null;
        }
        Matcher uuid = UUID.matcher(text);
        if (uuid.find()) {
            return uuid.group().toLowerCase(Locale.ROOT);
        }
        Matcher hex = HEX32.matcher(text);
        return hex.find() ? hex.group().toLowerCase(Locale.ROOT) : null;
    }

    /** Physical/eSIM carrier id of the default data subscription; -1 is "not reported". */
    private static String carrierId(Context context) {
        try {
            SubscriptionManager manager =
                    context.getSystemService(SubscriptionManager.class);
            if (manager == null) {
                return null;
            }
            List<SubscriptionInfo> subs = manager.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                return null;
            }
            int wanted = SubscriptionManager.getDefaultDataSubscriptionId();
            SubscriptionInfo chosen = subs.get(0);
            for (SubscriptionInfo candidate : subs) {
                if (candidate.getSubscriptionId() == wanted) {
                    chosen = candidate;
                    break;
                }
            }
            int id = chosen.getCarrierId();
            return id < 0 ? null : String.valueOf(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
