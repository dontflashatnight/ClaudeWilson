package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies a target {@link RiskModel} into the 6 orthogonal Diagnostic dimensions:
 * <ol>
 *   <li>{@link #DIM_STEALTH}: Stealth &amp; Footprint (target residue, su/packages/mounts/debug).</li>
 *   <li>{@link #DIM_INTEGRITY}: Integrity &amp; Hardware (Verified Boot, SELinux, StrongBox, TLS, server boundaries).</li>
 *   <li>{@link #DIM_NETLOC}: Network &amp; Location (dual-layer location, mock/GNSS, VPN/proxy, IPv6).</li>
 *   <li>{@link #DIM_IDENTITY}: Local Identity Chain (device IDs, Segment, Sift, Statsig, Sentry, Datadog, Firebase FID).</li>
 *   <li>{@link #DIM_ACCOUNT}: Account &amp; Session Artifacts (Play country, cookies, trusted device, country_code_hint).</li>
 *   <li>{@link #DIM_PERMS}: Permissions &amp; Sensors (runtime permissions, target process state, version audit).</li>
 * </ol>
 *
 * <p>Deliberately excludes region-control fields owned by PersonaPanel and historical Firelog
 * upload rows owned by the Persona-Match Ledger so nothing is duplicated across surfaces.
 */
public final class DiagnosticClassifier {

    public static final String DIM_STEALTH = "stealth";
    public static final String DIM_INTEGRITY = "integrity";
    public static final String DIM_NETLOC = "netloc";
    public static final String DIM_IDENTITY = "identity";
    public static final String DIM_ACCOUNT = "account";
    public static final String DIM_PERMS = "perms";

    public static final String ST_OK = "OK";
    public static final String ST_WARN = "WARN";
    public static final String ST_RISK = "RISK";
    public static final String ST_BOUNDARY = "BOUNDARY";
    public static final String ST_SILENT = "SILENT";

    public static final List<String> ALL_DIMS = Collections.unmodifiableList(Arrays.asList(
            DIM_STEALTH, DIM_INTEGRITY, DIM_NETLOC, DIM_IDENTITY, DIM_ACCOUNT, DIM_PERMS));

    private static final Set<String> EXCLUDED_IDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "system.sim_operator", "system.sim_iso", "system.network_iso",
                    "system.carrier", "system.timezone", "system.tz_offset",
                    "system.sales_code", "system.locale", "system.product_locale",
                    "system.build", "system.sim_carrier_id",
                    "target.payload.cc_hint",
                    "target.locale", "target.tz", "target.simop", "target.simiso",
                    "target.carrier", "target.build", "target.build_parts", "target.sdk",
                    "transport.datatransport.country", "transport.datatransport.locale",
                    "transport.datatransport.mcc", "transport.datatransport.tz",
                    "transport.datatransport.fingerprint", "transport.datatransport.build_parts",
                    "transport.datatransport.net_type", "transport.datatransport.mobile_subtype",
                    "transport.datatransport.os_build",
                    "sdk.firebase.country", "sdk.firebase.locale", "sdk.firebase.mcc",
                    "sdk.firebase.tz", "sdk.firebase.fingerprint")));

    private DiagnosticClassifier() {
    }

    public static final class Entry {
        public final CheckItem item;
        public final String dimension;
        public final String status;
        public final boolean isIdentity;

        public Entry(CheckItem item, String dimension, String status, boolean isIdentity) {
            this.item = item;
            this.dimension = dimension;
            this.status = status;
            this.isIdentity = isIdentity;
        }
    }

    public static final class DimensionGroup {
        public final String id;
        public final List<Entry> entries;
        public final int riskCount;
        public final int warnCount;
        public final int boundaryCount;
        public final int okCount;

        public DimensionGroup(String id, List<Entry> entries,
                              int riskCount, int warnCount, int boundaryCount, int okCount) {
            this.id = id;
            this.entries = Collections.unmodifiableList(entries);
            this.riskCount = riskCount;
            this.warnCount = warnCount;
            this.boundaryCount = boundaryCount;
            this.okCount = okCount;
        }
    }

    public static Map<String, DimensionGroup> classify(RiskModel model) {
        Map<String, List<Entry>> buckets = new LinkedHashMap<String, List<Entry>>();
        for (String dim : ALL_DIMS) {
            buckets.put(dim, new ArrayList<Entry>());
        }
        if (model != null) {
            for (String channel : model.channels()) {
                for (CheckItem item : model.items(channel)) {
                    if (item == null || EXCLUDED_IDS.contains(item.id())) {
                        continue;
                    }
                    String dim = dimensionFor(item);
                    String st = statusFor(item, dim);
                    boolean isId = isIdentityItem(item);
                    buckets.get(dim).add(new Entry(item, dim, st, isId));
                }
            }
        }
        Map<String, DimensionGroup> out = new LinkedHashMap<String, DimensionGroup>();
        for (String dim : ALL_DIMS) {
            List<Entry> list = buckets.get(dim);
            int risk = 0;
            int warn = 0;
            int boundary = 0;
            int ok = 0;
            for (Entry e : list) {
                if (ST_RISK.equals(e.status)) {
                    risk++;
                } else if (ST_WARN.equals(e.status)) {
                    warn++;
                } else if (ST_BOUNDARY.equals(e.status)) {
                    boundary++;
                } else {
                    ok++;
                }
            }
            out.put(dim, new DimensionGroup(dim, list, risk, warn, boundary, ok));
        }
        return out;
    }

    public static String dimensionFor(CheckItem item) {
        String id = item.id();
        String ch = item.channel();
        if (id.startsWith("target.su.") || "system.build_tags".equals(id)) {
            return DIM_STEALTH;
        }
        if ("integrity".equals(ch)) {
            return DIM_INTEGRITY;
        }
        if ("location".equals(ch) || "target.network_view".equals(id)
                || "target.network_addresses".equals(id) || "target.ipv6_spoof".equals(id)) {
            return DIM_NETLOC;
        }
        if ("identity".equals(ch) || "system.android_id".equals(id) || "target.aid".equals(id)) {
            return DIM_IDENTITY;
        }
        if ("account".equals(ch) || "transport".equals(ch)
                || "target.data.country_hint".equals(id)) {
            return DIM_ACCOUNT;
        }
        if ("permission".equals(ch) || "target.state".equals(id) || "target.version".equals(id)) {
            return DIM_PERMS;
        }
        return DIM_STEALTH;
    }

    public static boolean isIdentityItem(CheckItem item) {
        String id = item.id();
        if ("sdk.datadog.play_country".equals(id) || "sdk.statsig.locale".equals(id)) {
            return false;
        }
        return "identity".equals(item.channel())
                || "system.android_id".equals(id)
                || "target.aid".equals(id)
                || "account.local_handle".equals(id);
    }

    private static String statusFor(CheckItem item, String dim) {
        CheckItem.Verdict v = item.verdict();
        if (v == CheckItem.Verdict.CONFLICT) {
            return ST_RISK;
        }
        if (v == CheckItem.Verdict.BOUNDARY
                || item.writable() == CheckItem.Writable.BOUNDARY
                || item.coverage() == CheckItem.Coverage.SERVER_ONLY) {
            return ST_BOUNDARY;
        }
        // In zero-residue mode, target self-check files (hookcheck.txt) are deliberately disabled.
        // An unread self-check item is silent by design, not a warning requiring user action.
        if (!item.isRead()) {
            return ST_SILENT;
        }
        // Evaluate real risk/warning conditions even on stopped-process snapshots so genuine root
        // leaks are never masked when the target stops.
        if (DIM_STEALTH.equals(dim)) {
            String val = item.value() == null ? "" : item.value().toLowerCase(Locale.ROOT);
            if ((item.id().startsWith("target.su.") && !val.startsWith("0/") && !"clean".equals(val))
                    || ("system.build_tags".equals(item.id()) && !val.contains("release-keys"))) {
                return ST_RISK;
            }
        }
        if (DIM_PERMS.equals(dim) && item.id().startsWith("permission.")) {
            String val = item.value() == null ? "" : item.value().toLowerCase(Locale.ROOT);
            if (val.contains("granted") || val.contains("allow")) {
                return ST_WARN;
            }
        }
        // Clean snapshots captured before the target stopped are stale-silent (muted), not warnings.
        if (item.isStale()) {
            return ST_SILENT;
        }
        return ST_OK;
    }
}
