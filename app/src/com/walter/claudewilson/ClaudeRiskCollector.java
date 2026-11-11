package com.walter.claudewilson;

import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import com.walter.claudewilson.model.ClaudeChecklist;
import com.walter.claudewilson.model.RiskModel;
import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.platform.Diag;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Claude check items from the stores Claude itself writes.
 *
 * <p>The paths come from the analysis snapshot: the installation id, Segment prefs, the Sift
 * queue fingerprint, Sentry's native installation, Firebase's persisted installation, the
 * GrowthBook cache, the datatransport metadata and the local cookie prefs. A single root command
 * collects the files; tokens and cookie values are never copied out, only counts and the fields
 * the model names. The control app's own root/mount view is not a target fact.
 */
final class ClaudeRiskCollector {
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX32 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    RiskModel collect(Context context, TargetSpec spec, Snapshot snapshot, String playCountry) {
        if (spec == null || !"claude".equals(spec.id())) {
            return RiskModel.EMPTY;
        }
        Map<String, String> data = new LinkedHashMap<String, String>();
        Map<String, String> hook = new LinkedHashMap<String, String>();
        boolean root = RootShell.rootReady();
        boolean privileged = RootShell.shellReady();
        boolean running = false;
        boolean live = false;
        if (root && ShellQuote.packageName(spec.packageName())) {
            String dump = RootShell.run(script(spec.packageName()), 65536, 25000L);
            if (dump == null || dump.startsWith("exc:") || dump.startsWith("timeout:")
                    || !dump.contains("@@device_id")) {
                Diag.warn("risk", "claude file read failed for " + spec.packageName(), null);
            } else {
                Map<String, String> raw = sections(dump);
                parse(raw, data);
                applyTelemetry(context, spec, raw, data);
                if (!data.containsKey("target_app")) {
                    put(data, "target_app", packageVersion(context, spec.packageName()));
                }
                put(data, "installer", installer(context, spec.packageName()));
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
        if (!data.containsKey("target_app")) {
            put(data, "target_app", packageVersion(context, spec.packageName()));
        }
        if (!data.containsKey("build_parts")) {
            put(data, "build_parts", buildParts());
        }
        data.put("target_state", !privileged ? "unknown" : running ? "running" : "stopped");
        // The target's TLS state is a read of the same platform trust store it checks; no root
        // or target process is required for this fact.
        put(data, "tls_exposed", tlsState());
        String timezone = TimeZone.getDefault().getID();
        return ClaudeChecklist.build(data, hook, snapshot, timezone, playCountry, live);
    }

    private static void parse(Map<String, String> raw, Map<String, String> out) {
        put(out, "device_id", xml(raw.get("device_id"), "device_id"));

        String growthbook = raw.get("growthbook");
        put(out, "growthbook_unit", json(growthbook, "anonymousId", true));

        String segment = raw.get("segment_prefs");
        put(out, "segment_anonymous", xml(segment, "segment.anonymousId"));
        String segmentUser = xml(segment, "segment.userId");
        put(out, "segment_user", segmentUser);
        if (segmentUser == null && segment != null && !segment.trim().isEmpty()) {
            put(out, "segment_user_absent", "1");
        }
        put(out, "segment_device", xml(segment, "segment.device.id"));

        String trusted = raw.get("trusted_device");
        put(out, "trusted_device", trustedState(trusted));

        put(out, "sift_installation", firstId(raw.get("sift_installation")));
        put(out, "sift_fingerprint", firstHex64(raw.get("sift_fingerprint")));
        put(out, "sentry_installation", firstId(raw.get("sentry_installation")));
        put(out, "datadog_anonymous", firstId(raw.get("datadog")));
        put(out, "firebase_fid", json(raw.get("firebase_fid"), "Fid", false));

        String appPrefs = raw.get("app_prefs");
        put(out, "account_handle", xml(appPrefs, "app_last_account_id"));
        put(out, "account_plan", xml(appPrefs, "last_subscription_level"));

        String state = raw.get("analytics_state");
        put(out, "account_organization", xml(state, "identity_organization"));
        put(out, "account_subscription", xml(state, "identity_subscription_plan"));
        put(out, "target_app", appVersion(state));
        put(out, "build_parts", buildParts());

        Map<String, String> transport = keyPrefix(raw.get("transport"));
        if (transport != null) {
            put(out, "transport_country", transport.get("country"));
            put(out, "transport_locale", transport.get("locale"));
            put(out, "transport_mcc", transport.get("mcc_mnc"));
            put(out, "transport_tz", transport.get("tz-offset"));
            put(out, "transport_fingerprint", transport.get("fingerprint"));
            put(out, "transport_net_type", transport.get("net-type"));
            put(out, "transport_mobile_subtype", transport.get("mobile-subtype"));
            put(out, "transport_os_build", transport.get("os-build"));
            put(out, "transport_build", buildParts(transport.get("manufacturer"),
                    transport.get("device"), transport.get("product"),
                    transport.get("hardware"), transport.get("model")));
        }

        Map<String, String> cookies = counts(raw.get("cookies"));
        if (cookies != null) {
            put(out, "cfuvid", cookies.get("_cfuvid"));
            put(out, "session", cookies.get("sessionKeyV3"));
        }

        String payload = raw.get("payload");
        put(out, "payload_cc_hint", json(payload, "countryCodeHint", false));        put(out, "payload_locale", json(payload, "locale", false));
        put(out, "payload_tz", json(payload, "tz", false));
        put(out, "payload_simop", json(payload, "simOperator", false));
        put(out, "payload_simiso", json(payload, "simCountryIso", false));
        put(out, "payload_simname", json(payload, "simOperatorName", false));
        put(out, "payload_aid", json(payload, "androidId", false));
        String model = json(payload, "MODEL", false);
        String fingerprint = json(payload, "FINGERPRINT", false);
        if (model != null && fingerprint != null) {
            put(out, "payload_build", model + "|" + fingerprint);
        }
        put(out, "payload_build_parts", buildParts(
                json(payload, "MANUFACTURER", false),
                json(payload, "DEVICE", false),
                json(payload, "PRODUCT", false),
                json(payload, "HARDWARE", false),
                model));
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static String script(String pkg) {
        StringBuilder s = new StringBuilder();
        s.append("P=/proc/1/root/data/data/").append(pkg)
                .append("; [ -d \"$P\" ] || P=/data/data/").append(pkg).append(";\n");
        section(s, "device_id",
                "head -c 1024 \"$P/shared_prefs/device_id_prefs.xml\" 2>/dev/null");
        section(s, "segment_prefs",
                "F=$(ls \"$P\"/shared_prefs/analytics-android-*.xml 2>/dev/null | head -1); "
                        + "[ -n \"$F\" ] && head -c 8192 \"$F\"");
        section(s, "sift_installation",
                "head -c 128 \"$P/files/INSTALLATION\" 2>/dev/null");
        section(s, "sift_fingerprint",
                "F=\"$P/files/datastores/sift_queue.preferences_pb\"; "
                        + "[ -f \"$F\" ] && grep -a -o -E '[0-9a-fA-F]{64}' \"$F\" | tail -1");
        section(s, "sentry_installation",
                "find \"$P/cache/sentry\" -maxdepth 3 -name installation_id "
                        + "-exec head -1 {} \\; 2>/dev/null | head -1");
        section(s, "datadog",
                "F=$(ls -d \"$P\"/cache/datadog-* 2>/dev/null | head -1); "
                        + "[ -n \"$F\" ] && head -c 256 "
                        + "\"$F/datastore_v0/rum/anonymous_id_key\"");
        section(s, "firebase_fid",
                "F=$(ls \"$P\"/no_backup/PersistedInstallation.*.json 2>/dev/null | head -1); "
                        + "[ -n \"$F\" ] && grep -a -o '\"Fid\":\"[^\"]*\"' \"$F\" | head -1");
        section(s, "growthbook",
                "F=\"$P/files/global/global_bootstrap/cache.json\"; "
                        + "[ -f \"$F\" ] && grep -a -o '\"anonymousId\":\"[^\"]*\"' "
                        + "\"$F\" | tail -1");
        section(s, "app_prefs",
                "head -c 8192 \"$P/shared_prefs/app_prefs.xml\" 2>/dev/null");
        section(s, "analytics_state",
                "head -c 4096 \"$P/shared_prefs/analytics_state.xml\" 2>/dev/null");
        section(s, "trusted_device",
                "F=$(ls \"$P\"/shared_prefs/trusted_device_*.xml 2>/dev/null | head -1); "
                        + "if [ -n \"$F\" ]; then echo FILE=1; head -c 8192 \"$F\"; "
                        + "else echo FILE=0; fi");
        section(s, "transport",
                "F=\"$P/databases/com.google.android.datatransport.events\"; "
                        + "[ -f \"$F\" ] && strings \"$F\" | grep -a -E "
                        + "'^(country|locale|mcc_mnc|tz-offset|fingerprint|net-type|mobile-subtype|os-build|os-uild|manufacturer|model|device|product|hardware|sdk-version|application_build)' | tail -80");
        section(s, "journal",
                "F=\"$P/cache/cw-telemetry/events.jsonl\"; "
                        + "[ -f \"$F\" ] && cat \"$F\" 2>/dev/null");
        section(s, "cookies",
                "F=$(ls \"$P\"/shared_prefs/user_cookies_*.xml 2>/dev/null | head -1); "
                        + "if [ -n \"$F\" ]; then for N in _cfuvid sessionKeyV3; do "
                        + "C=$(grep -a -o \"$N\" \"$F\" | wc -l); echo \"$C $N\"; done; fi");
        section(s, "hookcheck",
                "cat \"$P/files/hookcheck.txt\" 2>/dev/null; "
                        + "for F in \"$P\"/files/hookcheck.*.txt; do "
                        + "[ -f \"$F\" ] || continue; "
                        + "echo \"process_file=$(basename \"$F\")\"; cat \"$F\"; done");
        section(s, "payload",
                "F=/data/local/tmp/claudewilson/claude.json; "
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

    /**
     * Merge the live queue with the persistent observation store. A successful datatransport
     * upload deletes its rows, so the live queue alone cannot answer what the target last saw.
     */
    private static void applyTelemetry(Context context, TargetSpec spec,
            Map<String, String> raw, Map<String, String> data) {
        String target = spec.id();
        String live = raw.get("transport");
        boolean readable = live == null
                || (!live.startsWith("exc:") && !live.startsWith("timeout:"));
        Map<String, String> transport = keyPrefix(live);
        TelemetryStore.ingest(context, target, transport, "datatransport");
        TelemetryStore.ingestJournal(context, target, raw.get("journal"));
        TelemetryStore.ingestDatabase(context, target, spec.packageName());

        Map<String, TelemetryStore.Entry> stored = TelemetryStore.read(context, target);
        fill(data, stored, "transport_country", "country");
        fill(data, stored, "transport_locale", "locale");
        fill(data, stored, "transport_mcc", "mcc_mnc");
        fill(data, stored, "transport_tz", "tz-offset");
        fill(data, stored, "transport_fingerprint", "fingerprint");
        fill(data, stored, "transport_net_type", "net-type");
        fill(data, stored, "transport_mobile_subtype", "mobile-subtype");
        fill(data, stored, "transport_os_build", "os-build");
        if (data.get("transport_build") == null) {
            String build = buildParts(TelemetryStore.latest(stored, "manufacturer"),
                    TelemetryStore.latest(stored, "device"),
                    TelemetryStore.latest(stored, "product"),
                    TelemetryStore.latest(stored, "hardware"),
                    TelemetryStore.latest(stored, "model"));
            if (build != null) {
                put(data, "transport_build", build);
            }
        }
        data.put("transport_readable", readable ? "1" : "0");

        Map<String, String> extra = new LinkedHashMap<String, String>();
        Map<String, String> hook = parseHook(raw.get("hookcheck"));
        for (Map.Entry<String, String> item : hook.entrySet()) {
            extra.put("hook." + item.getKey(), item.getValue());
        }
        Map<String, String> cookies = counts(raw.get("cookies"));
        if (cookies != null) {
            for (Map.Entry<String, String> item : cookies.entrySet()) {
                extra.put("cookie." + item.getKey(), item.getValue());
            }
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

    /** The datatransport metadata is stored as protobuf; strings leaves key+value pairs. */
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
                    out.put(canonical, clean(line.substring(key.length())));
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code strings} leaves a protobuf length byte or a backslash at the end of a value. */
    private static String clean(String value) {
        String text = value.trim();
        while (!text.isEmpty()) {
            char last = text.charAt(text.length() - 1);
            if (last == '\\' || last < 0x20) {
                text = text.substring(0, text.length() - 1).trim();
            } else {
                break;
            }
        }
        return text;
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

    private static String firstHex64(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = HEX64.matcher(text);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    private static String packageVersion(Context context, String pkg) {
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

    private static String appVersion(String state) {
        String version = xml(state, "last_app_version");
        String build = xml(state, "last_app_build");
        if (version == null || version.isEmpty()) {
            return build;
        }
        return build == null || build.isEmpty() ? version : version + " (" + build + ")";
    }

    private static String buildParts(String manufacturer, String device, String product,
                                     String hardware, String model) {
        if (manufacturer == null || device == null || product == null
                || hardware == null || model == null) {
            return null;
        }
        return manufacturer + "|" + device + "|" + product + "|" + hardware + "|" + model;
    }

    /**
     * A trusted-device prefs file that only carries the two EncryptedSharedPreferences keysets
     * has never stored a device binding.
     */
    private static String trustedState(String text) {
        if (text == null || !text.contains("FILE=1")) {
            return null;
        }
        Matcher matcher = Pattern.compile("<string name=\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!name.startsWith("__androidx_security_crypto_encrypted_prefs_")) {
                return "enrolled";
            }
        }
        return "empty";
    }

    /** True when the platform store still trusts any of the eight leaked eMudhra G3B/G3C CAs. */
    private static String tlsState() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = store.getCertificate(aliases.nextElement());
                if (!(certificate instanceof X509Certificate)) {
                    continue;
                }
                String dn = ((X509Certificate) certificate).getSubjectX500Principal()
                        .getName().toLowerCase(Locale.ROOT);
                if (dn.contains("emudhra") && (dn.contains("g3b") || dn.contains("g3c"))) {
                    return "exposed";
                }
            }
            return "clean";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String installer(Context context, String pkg) {
        try {
            InstallSourceInfo info = context.getPackageManager().getInstallSourceInfo(pkg);
            String installer = info == null ? null : info.getInstallingPackageName();
            return installer == null || installer.isEmpty() ? null : installer;
        } catch (Throwable t) {
            return null;
        }
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
