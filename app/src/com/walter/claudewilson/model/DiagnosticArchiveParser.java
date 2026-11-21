package com.walter.claudewilson.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure read-only parser that splits target apps' local diagnostic staging caches (Sentry
 * breadcrumbs/tags/envelopes, Datadog RUM last_view_event, and server config/bootstrap response
 * caches) into structured, human-readable records for {@code LedgerDb}.
 *
 * <p>Zero Android imports, zero CJK, zero raw tokens/cookies/query-secrets persisted.
 */
public final class DiagnosticArchiveParser {

    public static final String CAT_NETWORK = "network";
    public static final String CAT_RUNTIME = "runtime";
    public static final String CAT_DATADOG = "datadog";
    public static final String CAT_RESPONSE = "response";
    public static final String CAT_CRASH = "crash";

    private static final Pattern ISO_UTC = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?Z");
    private static final Pattern CF_RAY_POP = Pattern.compile("-([A-Z]{3})$");
    private static final Pattern URL_SPLIT = Pattern.compile(
            "^https?://([^/?#]+)(/[^?#]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_VAL_PAT = Pattern.compile("\"defaultValue\"\\s*:");
    private static final Pattern DEFAULT_TRUE_PAT = Pattern.compile("\"defaultValue\"\\s*:\\s*true\\b");
    private static final Pattern RULES_PAT = Pattern.compile("\"rules\"\\s*:\\s*\\[");
    private static final Pattern SLUG_PAT = Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"");

    private DiagnosticArchiveParser() {
    }

    public static final class HttpEvent {
        public final long occurredAt;
        public final String method;
        public final String host;
        public final String path;
        public final int statusCode;
        public final long durationMs;
        public final String cfRay;
        public final String edgePop;
        public final String requestId;
        public final String protocol;
        public final String tlsDiverted;
        public final String errorMessage;
        public final String dedupKey;

        public HttpEvent(long occurredAt, String method, String host, String path,
                         int statusCode, long durationMs, String cfRay, String edgePop,
                         String requestId, String protocol, String tlsDiverted,
                         String errorMessage, String dedupKey) {
            this.occurredAt = occurredAt;
            this.method = nn(method);
            this.host = nn(host);
            this.path = nn(path);
            this.statusCode = statusCode;
            this.durationMs = Math.max(0L, durationMs);
            this.cfRay = nn(cfRay);
            this.edgePop = nn(edgePop);
            this.requestId = nn(requestId);
            this.protocol = nn(protocol);
            this.tlsDiverted = nn(tlsDiverted);
            this.errorMessage = clip(nn(errorMessage), 180);
            this.dedupKey = nn(dedupKey);
        }

        public boolean isError() {
            return statusCode >= 400 || (statusCode == 0 && !errorMessage.isEmpty())
                    || "true".equalsIgnoreCase(tlsDiverted);
        }
    }

    public static final class DimSignal {
        public final String category;
        public final String signalKey;
        public final String signalValue;
        public final String sourceFile;
        public final long firstSeen;
        public final long lastSeen;
        public final int seenCount;

        public DimSignal(String category, String signalKey, String signalValue,
                         String sourceFile, long firstSeen, long lastSeen, int seenCount) {
            this.category = nn(category);
            this.signalKey = nn(signalKey);
            this.signalValue = clip(nn(signalValue), 160);
            this.sourceFile = nn(sourceFile);
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.seenCount = seenCount < 1 ? 1 : seenCount;
        }
    }

    public static final class Bundle {
        public final List<HttpEvent> httpEvents;
        public final List<DimSignal> dimSignals;
        public final String datadogLocale;

        public Bundle(List<HttpEvent> httpEvents, List<DimSignal> dimSignals,
                      String datadogLocale) {
            this.httpEvents = Collections.unmodifiableList(httpEvents);
            this.dimSignals = Collections.unmodifiableList(dimSignals);
            this.datadogLocale = nn(datadogLocale);
        }
    }

    /** Parses all staging sections from a collector dump into structured records. */
    public static Bundle parse(String targetId, Map<String, String> raw, long nowMs) {
        if (raw == null || raw.isEmpty()) {
            return new Bundle(Collections.<HttpEvent>emptyList(),
                    Collections.<DimSignal>emptyList(), "");
        }
        Map<String, HttpEvent> httpByKey = new LinkedHashMap<String, HttpEvent>();
        Map<String, DimSignal> sigByKey = new LinkedHashMap<String, DimSignal>();
        String datadogLocale = "";

        // 1. Sentry breadcrumbs (ring buffer containing HTTP, network, analytics & error events)
        String breadcrumbs = raw.get("sentry_breadcrumbs");
        if (breadcrumbs != null && !breadcrumbs.isEmpty()) {
            parseBreadcrumbs(breadcrumbs, nowMs, httpByKey, sigByKey);
        }

        // 2. Sentry scope tags.json
        String tags = raw.get("sentry_tags_full");
        if (tags != null && !tags.isEmpty()) {
            parseSentryTags(tags, nowMs, sigByKey);
        }

        // 3. Pending Sentry crash/error .envelope files
        String envRaw = raw.get("sentry_envelopes");
        if (envRaw != null && !envRaw.trim().isEmpty()) {
            int count = parseIntSafe(envRaw.trim(), -1);
            if (count > 0) {
                putSig(sigByKey, CAT_CRASH, "pending_envelopes", String.valueOf(count),
                        "sentry:envelope", nowMs);
            }
        }

        // 4. Datadog RUM last_view_event (binary header + JSON payload)
        String ddView = raw.get("datadog_view");
        if (ddView != null && !ddView.isEmpty()) {
            datadogLocale = parseDatadogView(ddView, nowMs, sigByKey);
        }

        // 5. Server response caches (Claude global_bootstrap / ChatGPT models & account cache)
        String bootstrap = raw.get("bootstrap_cache");
        if (bootstrap != null && !bootstrap.isEmpty()) {
            parseClaudeBootstrap(bootstrap, nowMs, sigByKey);
        }
        String respCache = raw.get("chatgpt_resp_cache");
        if (respCache != null && !respCache.isEmpty()) {
            parseChatGptRespCache(respCache, nowMs, sigByKey);
        }

        List<HttpEvent> events = new ArrayList<HttpEvent>(httpByKey.values());
        Collections.sort(events, new Comparator<HttpEvent>() {
            @Override public int compare(HttpEvent a, HttpEvent b) {
                return Long.compare(b.occurredAt, a.occurredAt);
            }
        });
        List<DimSignal> signals = new ArrayList<DimSignal>(sigByKey.values());
        return new Bundle(events, signals, datadogLocale);
    }

    private static void parseBreadcrumbs(String raw, long nowMs,
                                         Map<String, HttpEvent> httpOut,
                                         Map<String, DimSignal> sigOut) {
        List<String> objs = extractTopLevelJsonObjects(raw);
        // Track latest cronet protocol/tls hints by endpoint path to enrich HTTP breadcrumbs.
        Map<String, String> endpointProto = new LinkedHashMap<String, String>();
        Map<String, String> endpointTls = new LinkedHashMap<String, String>();

        for (String obj : objs) {
            String cat = jsonStr(obj, "category");
            String msg = jsonStr(obj, "message");
            if ("Analytics Event".equals(cat) && msg != null) {
                if (msg.contains("cronet.request_success")) {
                    String ep = jsonStr(obj, "endpoint");
                    String proto = jsonStr(obj, "protocol");
                    String tls = jsonStr(obj, "tls_diverted");
                    if (ep != null && !ep.isEmpty()) {
                        if (proto != null && !proto.isEmpty()) endpointProto.put(ep, proto);
                        if (tls != null && !tls.isEmpty()) endpointTls.put(ep, tls);
                    }
                    if (tls != null && !tls.isEmpty()) {
                        putSig(sigOut, CAT_NETWORK, "tls_diverted", tls, "sentry:breadcrumbs", nowMs);
                    }
                } else if (msg.contains("cronet.initialization_succeeded")) {
                    String prov = jsonStr(obj, "provider_name");
                    String ver = jsonStr(obj, "provider_version");
                    if (prov != null && !prov.isEmpty()) {
                        String val = ver != null && !ver.isEmpty() ? prov + " " + ver : prov;
                        putSig(sigOut, CAT_RUNTIME, "cronet_provider", val, "sentry:breadcrumbs", nowMs);
                    }
                } else if (msg.contains("process_started")) {
                    addIfPresent(sigOut, CAT_RUNTIME, "sign_in_browser",
                            jsonStr(obj, "sign_in_browser"), "sentry:breadcrumbs", nowMs);
                    addIfPresent(sigOut, CAT_RUNTIME, "sign_in_custom_tabs",
                            jsonStr(obj, "sign_in_browser_custom_tabs"), "sentry:breadcrumbs", nowMs);
                    String wvPkg = jsonStr(obj, "webview_package");
                    String wvVer = jsonStr(obj, "webview_version");
                    if (wvPkg != null && !wvPkg.isEmpty()) {
                        String val = wvVer != null && !wvVer.isEmpty() ? wvPkg + " " + wvVer : wvPkg;
                        putSig(sigOut, CAT_RUNTIME, "webview_runtime", val, "sentry:breadcrumbs", nowMs);
                    }
                    addIfPresent(sigOut, CAT_RUNTIME, "play_services_status",
                            jsonStr(obj, "play_services_status"), "sentry:breadcrumbs", nowMs);
                    addIfPresent(sigOut, CAT_RUNTIME, "ram_tier",
                            jsonStr(obj, "ram_tier"), "sentry:breadcrumbs", nowMs);
                }
            } else if ("network.event".equals(cat)) {
                String vpn = jsonRawScalar(obj, "vpn_active");
                String netType = jsonStr(obj, "network_type");
                if (vpn != null && !vpn.isEmpty()) {
                    putSig(sigOut, CAT_NETWORK, "vpn_active", vpn, "sentry:breadcrumbs", nowMs);
                }
                if (netType != null && !netType.isEmpty()) {
                    putSig(sigOut, CAT_NETWORK, "network_type", netType, "sentry:breadcrumbs", nowMs);
                }
            }
        }

        for (String obj : objs) {
            String cat = jsonStr(obj, "category");
            String type = jsonStr(obj, "type");
            if (!"http".equals(cat) && !"http".equals(type)) {
                String level = jsonStr(obj, "level");
                if ("error".equalsIgnoreCase(level) || "fatal".equalsIgnoreCase(level)) {
                    String err = jsonStr(obj, "message");
                    if (err == null || err.isEmpty()) {
                        err = jsonStr(obj, "error_message");
                    }
                    if (err != null && !err.isEmpty()) {
                        putSig(sigOut, CAT_CRASH, "recent_error", err, "sentry:breadcrumbs", nowMs);
                    }
                }
                continue;
            }
            String url = jsonStr(obj, "url");
            String host = jsonStr(obj, "host");
            String path = jsonStr(obj, "path");
            if (url != null && !url.isEmpty()) {
                Matcher um = URL_SPLIT.matcher(url);
                if (um.find()) {
                    if (host == null || host.isEmpty()) host = um.group(1);
                    if (path == null || path.isEmpty()) path = um.group(2) != null ? um.group(2) : "/";
                }
            }
            if (host == null || host.isEmpty()) {
                continue;
            }
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String method = nn(jsonStr(obj, "method")).toUpperCase(Locale.ROOT);
            if (method.isEmpty()) method = "GET";
            int status = parseIntSafe(jsonRawScalar(obj, "status_code"), 0);
            long startMs = parseLongSafe(jsonRawScalar(obj, "http.start_timestamp"), 0L);
            long endMs = parseLongSafe(jsonRawScalar(obj, "http.end_timestamp"), 0L);
            long ts = parseIsoUtcMillis(jsonStr(obj, "timestamp"));
            long occurredAt = endMs > 0L ? endMs : (ts > 0L ? ts : nowMs);
            long dur = (startMs > 0L && endMs >= startMs) ? (endMs - startMs) : 0L;
            String cfRay = nn(jsonStr(obj, "cf_ray"));
            String pop = extractEdgePop(cfRay);
            String reqId = nn(jsonStr(obj, "request_id"));
            String errMsg = nn(jsonStr(obj, "error_message"));
            String proto = nn(endpointProto.get(path));
            String tls = nn(endpointTls.get(path));

            String dedup = occurredAt + "|" + method + "|" + host + "|" + path + "|"
                    + status + "|" + reqId + "|" + cfRay;
            httpOut.put(dedup, new HttpEvent(occurredAt, method, host, path, status, dur,
                    cfRay, pop, reqId, proto, tls, errMsg, dedup));

            if (!pop.isEmpty()) {
                putSig(sigOut, CAT_NETWORK, "cf_edge_pop", pop, "sentry:breadcrumbs", occurredAt);
            }
        }
    }

    private static void parseSentryTags(String raw, long nowMs, Map<String, DimSignal> sigOut) {
        List<String> objs = extractTopLevelJsonObjects(raw);
        if (objs.isEmpty()) return;
        String obj = objs.get(0);
        addIfPresent(sigOut, CAT_RUNTIME, "active_screen", jsonStr(obj, "active_screen"), "sentry:tags", nowMs);
        addIfPresent(sigOut, CAT_RUNTIME, "start_destination", jsonStr(obj, "app_start_destination"), "sentry:tags", nowMs);
        addIfPresent(sigOut, CAT_RUNTIME, "api_environment", jsonStr(obj, "apiEnvironment"), "sentry:tags", nowMs);
        addIfPresent(sigOut, CAT_RUNTIME, "play_country_gate",
                jsonStr(obj, "layer.play_store_country_enabled"), "sentry:tags", nowMs);
        String wvPkg = jsonStr(obj, "webview_package");
        String wvVer = jsonStr(obj, "webview_version");
        if (wvPkg != null && !wvPkg.isEmpty() && !sigOut.containsKey(CAT_RUNTIME + "|webview_runtime")) {
            String val = wvVer != null && !wvVer.isEmpty() ? wvPkg + " " + wvVer : wvPkg;
            putSig(sigOut, CAT_RUNTIME, "webview_runtime", val, "sentry:tags", nowMs);
        }
    }

    private static String parseDatadogView(String raw, long nowMs, Map<String, DimSignal> sigOut) {
        List<String> objs = extractTopLevelJsonObjects(raw);
        String locale = "";
        for (String obj : objs) {
            long dt = parseLongSafe(jsonRawScalar(obj, "date"), nowMs);
            String loc = jsonStr(obj, "current_locale");
            if (loc != null && !loc.isEmpty()) {
                locale = loc;
                putSig(sigOut, CAT_DATADOG, "datadog_locale", loc, "datadog:last_view", dt);
            }
            addIfPresent(sigOut, CAT_DATADOG, "datadog_service", jsonStr(obj, "service"), "datadog:last_view", dt);
            String viewName = parseSectionString(obj, "view", "name");
            if (viewName == null || viewName.isEmpty()) {
                viewName = jsonStr(obj, "name");
            }
            if (viewName != null && !viewName.isEmpty()) {
                putSig(sigOut, CAT_DATADOG, "datadog_view", viewName, "datadog:last_view", dt);
            }
            int errCount = parseSectionCount(obj, "error");
            int crashCount = parseSectionCount(obj, "crash");
            int resCount = parseSectionCount(obj, "resource");
            if (errCount >= 0 || crashCount >= 0 || resCount >= 0) {
                String summary = "res=" + Math.max(0, resCount)
                        + " err=" + Math.max(0, errCount)
                        + " crash=" + Math.max(0, crashCount);
                putSig(sigOut, CAT_DATADOG, "datadog_rum_counts", summary, "datadog:last_view", dt);
            }
        }
        return locale;
    }

    private static void parseClaudeBootstrap(String raw, long nowMs, Map<String, DimSignal> sigOut) {
        if (!raw.contains("\"growthbook\"") && !raw.contains("\"features\"")) {
            return;
        }
        int total = countMatches(DEFAULT_VAL_PAT, raw);
        int enabled = countMatches(DEFAULT_TRUE_PAT, raw);
        int rules = countMatches(RULES_PAT, raw);
        long cachedAt = parseLongSafe(jsonRawScalar(raw, "cachedAtMillis"), nowMs);
        if (total > 0) {
            String summary = enabled + "/" + total + " on · " + rules + " rules";
            putSig(sigOut, CAT_RESPONSE, "bootstrap_features", summary, "response:bootstrap", cachedAt);
        }
        String alg = jsonStr(raw, "hashing_algorithm");
        if (alg != null && !alg.isEmpty()) {
            putSig(sigOut, CAT_RESPONSE, "bootstrap_hash", alg, "response:bootstrap", cachedAt);
        }
    }

    private static void parseChatGptRespCache(String raw, long nowMs, Map<String, DimSignal> sigOut) {
        int models = countMatches(SLUG_PAT, raw);
        if (models > 0) {
            putSig(sigOut, CAT_RESPONSE, "models_cached", String.valueOf(models), "response:models", nowMs);
        }
        addIfPresent(sigOut, CAT_RESPONSE, "account_plan_cache",
                jsonStr(raw, "plan_type"), "response:account", nowMs);
        addIfPresent(sigOut, CAT_RESPONSE, "account_country_cache",
                jsonStr(raw, "country"), "response:account", nowMs);
        String statsigCount = jsonRawScalar(raw, "statsig_cache_bytes");
        if (statsigCount != null && !statsigCount.isEmpty()) {
            long bytes = parseLongSafe(statsigCount, 0L);
            if (bytes > 0L) {
                putSig(sigOut, CAT_RESPONSE, "statsig_cache_kb",
                        (bytes / 1024L) + " KB", "response:statsig", nowMs);
            }
        }
    }

    /** Extracts balanced top-level JSON objects from text that may include binary headers. */
    public static List<String> extractTopLevelJsonObjects(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        int n = raw.length();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                if (depth > 0) {
                    inStr = true;
                }
            } else if (c == '{') {
                if (depth == 0) {
                    // Sentry/Datadog binary ring-buffer length headers can end in byte 0x7b ('{')
                    // right before '{"timestamp":...}'. Only open a top-level object when '{' is
                    // followed by '"' (valid JSON key start) or '}' (empty object).
                    int k = i + 1;
                    while (k < n && (raw.charAt(k) == ' ' || raw.charAt(k) == '\t'
                            || raw.charAt(k) == '\r' || raw.charAt(k) == '\n')) {
                        k++;
                    }
                    if (k < n && (raw.charAt(k) == '"' || raw.charAt(k) == '}')) {
                        start = i;
                        depth = 1;
                    }
                } else {
                    depth++;
                }
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        out.add(raw.substring(start, i + 1));
                        start = -1;
                    }
                }
            } else if (depth > 0 && c < 0x20 && c != '\t' && c != '\r' && c != '\n') {
                // Unescaped binary control byte outside a JSON string means a stray header frame.
                depth = 0;
                start = -1;
            }
        }
        return out;
    }

    public static String extractEdgePop(String cfRay) {
        if (cfRay == null || cfRay.isEmpty()) {
            return "";
        }
        Matcher m = CF_RAY_POP.matcher(cfRay.trim().toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }

    public static long parseIsoUtcMillis(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0L;
        }
        Matcher m = ISO_UTC.matcher(iso.trim());
        if (!m.find()) {
            return 0L;
        }
        try {
            String base = m.group(1) + "-" + m.group(2) + "-" + m.group(3)
                    + "T" + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            long sec = fmt.parse(base).getTime();
            String msStr = m.group(7);
            if (msStr != null && !msStr.isEmpty()) {
                while (msStr.length() < 3) msStr += "0";
                sec += Long.parseLong(msStr);
            }
            return sec;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Appends structured HTTP &amp; multi-dimension staging tables to offline Markdown reports. */
    public static void appendMarkdown(StringBuilder sb, List<HttpEvent> httpEvents,
                                      List<DimSignal> signals) {
        if (sb == null) return;
        if (httpEvents != null && !httpEvents.isEmpty()) {
            sb.append("\n## Target HTTP Response Archive\n\n");
            sb.append("| Status | Method | Host | Path | Edge | Latency | TLS Diverted | Error |\n");
            sb.append("|---:|---|---|---|---|---:|---|---|\n");
            int limit = Math.min(httpEvents.size(), 40);
            for (int i = 0; i < limit; i++) {
                HttpEvent e = httpEvents.get(i);
                sb.append("| ").append(e.statusCode)
                        .append(" | `").append(e.method).append('`')
                        .append(" | `").append(e.host).append('`')
                        .append(" | `").append(e.path).append('`')
                        .append(" | ").append(e.edgePop.isEmpty() ? "-" : e.edgePop)
                        .append(" | ").append(e.durationMs > 0 ? e.durationMs + "ms" : "-")
                        .append(" | ").append(e.tlsDiverted.isEmpty() ? "-" : e.tlsDiverted)
                        .append(" | ").append(e.errorMessage.isEmpty() ? "-" : e.errorMessage.replace('|', '/'))
                        .append(" |\n");
            }
        }
        if (signals != null && !signals.isEmpty()) {
            sb.append("\n## Target Staging Diagnostics\n\n");
            sb.append("| Dimension | Key | Value | Source | Count |\n");
            sb.append("|---|---|---|---|---:|\n");
            for (DimSignal s : signals) {
                sb.append("| `").append(s.category).append('`')
                        .append(" | `").append(s.signalKey).append('`')
                        .append(" | `").append(s.signalValue.replace('|', '/')).append('`')
                        .append(" | ").append(s.sourceFile)
                        .append(" | ").append(s.seenCount)
                        .append(" |\n");
            }
        }
    }

    private static void addIfPresent(Map<String, DimSignal> out, String cat, String key,
                                     String val, String src, long ts) {
        if (val != null && !val.trim().isEmpty()) {
            putSig(out, cat, key, val.trim(), src, ts);
        }
    }

    private static void putSig(Map<String, DimSignal> out, String cat, String key,
                               String val, String src, long ts) {
        String id = cat + "|" + key;
        out.put(id, new DimSignal(cat, key, val, src, ts, ts, 1));
    }

    private static int parseSectionCount(String json, String sectionKey) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(sectionKey)
                + "\"\\s*:\\s*\\{[^}]*\"count\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? parseIntSafe(m.group(1), -1) : -1;
    }

    private static String parseSectionString(String json, String sectionKey, String fieldKey) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(sectionKey)
                + "\"\\s*:\\s*\\{[^}]*\"" + Pattern.quote(fieldKey)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int countMatches(Pattern p, String text) {
        int c = 0;
        Matcher m = p.matcher(text);
        while (m.find()) c++;
        return c;
    }

    private static String jsonStr(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        return m.group(1)
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String jsonRawScalar(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(true|false|-?\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static String clip(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 3) + "...";
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
