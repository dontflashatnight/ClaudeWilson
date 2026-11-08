package com.walter.claudewilson;

import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Region-switch reference data. Each entry lists the field values a device
 * should present for that region (telemetry-SDK-visible fields first, then firmware
 * hints). zones/locales are the applyable pools — random pick must stay
 * consistent with the region logic.
 */
public final class Regions {

    public static final class R {
        public final String name, iso, mcc, utc, carriers, note;
        public final String[] zones, locales;
        R(String name, String iso, String mcc, String utc,
          String[] zones, String[] locales, String carriers, String note) {
            this.name = name; this.iso = iso; this.mcc = mcc; this.utc = utc;
            this.zones = zones; this.locales = locales;
            this.carriers = carriers; this.note = note;
        }
        public String dispTz() { return zones[0]; }
        public String dispLocale() { return String.join("/", locales); }
        /** country name in the CURRENT UI language — auto-covers every
         *  applied locale, no per-language table needed */
        public String dispName() {
            return new Locale("", iso.toUpperCase(Locale.ROOT))
                    .getDisplayCountry();
        }

        private String key;
        /** substring match against: iso2, iso3, english name, endonym
         *  (own-language name), curated name, current-locale name, alias */
        public boolean matches(String q) {
            q = q.toLowerCase(Locale.ROOT).trim();
            if (q.isEmpty()) return true;
            if (key == null) key = buildKey();
            if (key.contains(q)
                    || dispName().toLowerCase(Locale.ROOT).contains(q))
                return true;
            String via = ALIAS.get(q);
            return via != null && via.equals(iso);
        }

        private String buildKey() {
            StringBuilder b = new StringBuilder(iso);
            Locale c = new Locale("", iso.toUpperCase(Locale.ROOT));
            b.append(' ').append(c.getDisplayCountry(Locale.ENGLISH)
                    .toLowerCase(Locale.ROOT));
            try {
                b.append(' ').append(c.getISO3Country()
                        .toLowerCase(Locale.ROOT));
            } catch (Throwable ignored) {}
            for (String loc : locales) {
                String[] p = loc.split("-");
                if (p.length == 2)
                    b.append(' ').append(c.getDisplayCountry(
                            new Locale(p[0], p[1])).toLowerCase(Locale.ROOT));
            }
            if (!name.isEmpty())
                b.append(' ').append(name.toLowerCase(Locale.ROOT));
            b.append(' ').append(c.getDisplayCountry(Locale.CHINESE)
                    .toLowerCase(Locale.ROOT))
             .append(' ').append(c.getDisplayCountry(Locale.JAPANESE)
                    .toLowerCase(Locale.ROOT));
            return b.toString();
        }
    }

    /** common non-iso aliases → iso (people type "uk" but iso is "gb") */
    static final Map<String, String> ALIAS = new HashMap<>();
    static {
        String[][] a = {
            {"uk","gb"},{"england","gb"},{"britain","gb"},
            {"uae","ae"},{"emirates","ae"},{"usa","us"},
            {"holland","nl"},{"southkorea","kr"},{"korea","kr"},
            {"ivorycoast","ci"},{"czech","cz"},{"timor","tl"},
            {"burma","mm"},{"persia","ir"},{"tuerkiye","tr"},
        };
        for (String[] p : a) ALIAS.put(p[0], p[1]);
    }

    static String[] z(String... v) { return v; }
    static String[] l(String... v) { return v; }

    private static List<R> core = new ArrayList<>();

    /** T-1 官方支持区(help center 全量清单)——iso 小写,官方字母序 */
    public static final String[] SUPPORTED = {
        "al","dz","ad","ao","ag","ar","am","au","at","az","bs","bh","bd","bb",
        "be","bz","bj","bt","bo","ba","bw","br","bn","bg","bf","bi","kh","cm",
        "ca","cv","td","cl","co","km","cg","cr","hr","cy","cz","dk","dj",
        "dm","do","tl","ec","eg","sv","gq","ee","sz","fj","fi","fr","ga","gm",
        "ge","de","gh","gr","gd","gt","gn","gw","gy","ht","hn","hu","is","in",
        "id","iq","ie","il","it","ci","jm","jp","jo","kz","ke","ki","kw","kg",
        "la","lv","lb","ls","lr","li","lt","lu","mg","mw","my","mv","mt","mp",
        "mh","mr","mu","mx","fm","md","mc","mn","me","ma","mz","na","nr","np",
        "nl","nz","ni","ne","ng","mk","no","om","pk","pw","ps","pa","pg","py",
        "pe","ph","pl","pt","qa","ro","rw","kn","lc","vc","ws","sm","st","sa",
        "sn","rs","sc","sl","sg","sk","si","sb","za","kr","es","lk","sr","se",
        "ch","tw","tj","tz","th","tg","to","tt","tn","tr","tm","tv","ug","ua",
        "ae","gb","us","uy","uz","vu","va","vn","zm","zw"
    };

    public static List<R> ALL = Collections.emptyList();

    /**
     * Loads the region table and appends a generated entry for every supported country that has
     * no curated row. Called once from the dashboard; reading the asset needs a context and ALL
     * is used statically everywhere else.
     */
    public static synchronized void init(Context ctx) {
        if (!ALL.isEmpty()) {
            return;
        }
        core = loadCore(ctx);
        List<R> all = new ArrayList<>(core);
        for (String iso : SUPPORTED) {
            boolean dup = false;
            for (R r : core) {
                if (r.iso.equals(iso)) { dup = true; break; }
            }
            if (!dup) all.add(auto(iso));
        }
        ALL = Collections.unmodifiableList(all);
    }

    /** Rows come from catalog/regions.txt; a missing file degrades to the generated entries. */
    private static List<R> loadCore(Context ctx) {
        List<R> rows = new ArrayList<>();
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    ctx.getAssets().open("catalog/regions.txt"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\|", -1);
                if (f.length < 8) continue;
                rows.add(new R(f[0], f[1], f[2], f[3], f[4].split(";"), f[5].split(";"),
                        f[6], f[7]));
            }
        } catch (Exception e) {
            android.util.Log.w("ClaudeWilson", "regions: cannot load catalog", e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return rows;
    }

    /** auto-derived entry for a supported iso: zones/utc via ICU,
     *  locale via ICU likely-subtags, mcc via system MccTable */
    private static R auto(String iso) {
        String[] zones = icuZones(iso);
        String lang = likelyLang(iso);
        String[] locs = lang.isEmpty() || "en".equals(lang)
                ? new String[]{"en-" + iso}
                : new String[]{lang + "-" + iso, "en-" + iso};
        return new R("", iso, mccFor(iso), utcRange(zones), zones, locs, "", "");
    }

    static String[] icuZones(String iso) {
        try {
            String[] z = android.icu.util.TimeZone
                    .getAvailableIDs(iso.toUpperCase(Locale.ROOT));
            if (z.length > 0) return z;
        } catch (Throwable ignored) {}
        return new String[]{"UTC"};
    }

    /** prefer zones that are their own CLDR canonical (Asia/Chongqing
     *  is an alias → canonical Asia/Shanghai wins) */
    static String primaryZone(String iso) {
        String[] zones = icuZones(iso);
        for (String z : zones) {
            try {
                if (z.equals(android.icu.util.TimeZone.getCanonicalID(z)))
                    return z;
            } catch (Throwable ignored) {}
        }
        return zones[0];
    }

    static String likelyLang(String iso) {
        try {
            android.icu.util.ULocale ul = android.icu.util.ULocale
                    .addLikelySubtags(android.icu.util.ULocale
                            .forLanguageTag("und-"
                                    + iso.toUpperCase(Locale.ROOT)));
            String l = ul.getLanguage();
            return l == null || l.isEmpty() || "und".equals(l) ? "" : l;
        } catch (Throwable t) { return ""; }
    }

    static String utcRange(String[] zones) {
        long now = System.currentTimeMillis();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (String id : zones) {
            int m = TimeZone.getTimeZone(id).getOffset(now) / 60000;
            if (m < min) min = m;
            if (m > max) max = m;
        }
        if (min == Integer.MAX_VALUE) return "UTC";
        return "UTC" + utcFmt(min) + (max != min ? "~" + utcFmt(max) : "");
    }

    static String utcFmt(int m) {
        int a = Math.abs(m);
        return (m >= 0 ? "+" : "-") + (a / 60)
                + (a % 60 == 0 ? "" : ":" + (a % 60 < 10 ? "0" : "") + a % 60);
    }

    /** Current UTC text for a zone id; callers pass the system property, not a cached TimeZone. */
    static String utcAt(String zone) {
        TimeZone t = zone == null || zone.isEmpty()
                ? TimeZone.getDefault() : TimeZone.getTimeZone(zone);
        return "UTC" + utcFmt(t.getOffset(System.currentTimeMillis()) / 60000);
    }

    private static volatile Map<String, String> mccMap;
    static String mccFor(String iso) {
        Map<String, String> m = mccMap;
        if (m == null) { m = buildMccMap(); mccMap = m; }
        String v = m.get(iso);
        return v == null ? "" : v;
    }

    /** system ITU table via com.android.internal.telephony.MccTable (hidden) */
    private static Map<String, String> buildMccMap() {
        Map<String, String> m = new HashMap<>();
        try {
            Class<?> mt = Class.forName("com.android.internal.telephony.MccTable");
            java.util.Collection<?> all = null;
            try {
                all = (java.util.Collection<?>)
                        mt.getMethod("allMccs").invoke(null);
            } catch (NoSuchMethodException e1) {
                try {
                    java.lang.reflect.Field f = mt.getDeclaredField("sTable");
                    f.setAccessible(true);
                    all = (java.util.Collection<?>) f.get(null);
                } catch (Throwable ignored) {}
            }
            if (all != null) {
                for (Object e : all) putMcc(m, e);
                return m;
            }
            // OneUI15: no bulk table — walk entryForMcc(int) instead
            java.lang.reflect.Method em = mt.getMethod("entryForMcc", int.class);
            for (int mcc = 200; mcc < 900; mcc++) {
                try { putMcc(m, em.invoke(null, mcc)); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private static void putMcc(Map<String, String> m, Object e) {
        if (e == null) return;
        String i = fieldStr(e, "mIso", "iso");
        String mcc = fieldStr(e, "mMcc", "mcc");
        if (i == null || mcc == null || mcc.isEmpty()) return;
        i = i.toLowerCase(Locale.ROOT);
        String cur = m.get(i);
        if (cur == null) m.put(i, mcc);
        else if (!Arrays.asList(cur.split("/")).contains(mcc))
            m.put(i, cur + "/" + mcc);
    }

    /** mcc → iso via the same hidden ITU table ("460" → "cn") */
    static String isoForMcc(int mcc) {
        try {
            Class<?> mt = Class.forName("com.android.internal.telephony.MccTable");
            Object r = mt.getMethod("countryCodeForMcc", int.class)
                    .invoke(null, mcc);
            return r == null ? null : r.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) { return null; }
    }

    private static String fieldStr(Object e, String... names) {
        for (String n : names) {
            try {
                java.lang.reflect.Field f =
                        e.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(e);
                if (v instanceof String) return (String) v;
                if (v instanceof Integer) return String.valueOf(v);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final java.util.Random RNG = new java.util.Random();
    private static volatile Map<String, String[]> TABLE_ZONES;

    /** random zone+locale pick; the zone comes from the full IANA table for this country. */
    public static String[] pick(R r) {
        String[] pool = tableZones(r.iso);
        String zone = pool != null && pool.length > 0
                ? pool[RNG.nextInt(pool.length)]
                : r.zones[RNG.nextInt(r.zones.length)];
        return new String[]{zone, r.locales[RNG.nextInt(r.locales.length)]};
    }

    /**
     * Every canonical IANA zone whose ICU region is this country. This is the same table the
     * timezone picker shows, so a US region can land on any US zone, not only its first entry.
     */
    private static String[] tableZones(String iso) {
        Map<String, String[]> cache = TABLE_ZONES;
        if (cache == null) {
            synchronized (Regions.class) {
                cache = TABLE_ZONES;
                if (cache == null) {
                    cache = buildTableZones();
                    TABLE_ZONES = cache;
                }
            }
        }
        return cache.get(iso.toUpperCase(Locale.ROOT));
    }

    private static Map<String, String[]> buildTableZones() {
        Map<String, Set<String>> found = new HashMap<String, Set<String>>();
        for (String id : java.util.TimeZone.getAvailableIDs()) {
            try {
                String region = android.icu.util.TimeZone.getRegion(id);
                if (region == null || region.length() != 2) {
                    continue;
                }
                region = region.toUpperCase(Locale.ROOT);
                if ("ZZ".equals(region)) {
                    continue;
                }
                String canonical = android.icu.util.TimeZone.getCanonicalID(id);
                if (canonical == null || canonical.isEmpty()) {
                    continue;
                }
                Set<String> zones = found.get(region);
                if (zones == null) {
                    zones = new java.util.LinkedHashSet<String>();
                    found.put(region, zones);
                }
                zones.add(canonical);
            } catch (Throwable ignored) {
            }
        }
        Map<String, String[]> out = new HashMap<String, String[]>();
        for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
            out.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return out;
    }

    /** "jp" → 🇯🇵 regional-indicator pair */
    public static String flagOf(String iso) {
        StringBuilder b = new StringBuilder();
        for (char c : iso.toUpperCase(Locale.ROOT).toCharArray())
            b.appendCodePoint(0x1F1E6 + (c - 'A'));
        return b.toString();
    }

    public static boolean mccMatch(String expected, String actual) {
        try {
            int a = Integer.parseInt(actual);
            for (String tok : expected.split("/")) {
                if (tok.contains("-")) {
                    String[] e = tok.split("-");
                    if (a >= Integer.parseInt(e[0]) && a <= Integer.parseInt(e[1]))
                        return true;
                } else if (Integer.parseInt(tok) == a) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean utcMatch(String expected, String actual) {
        Integer a = parseMin(actual);
        if (a == null) return false;
        for (String seg : expected.substring(3).split("/")) {
            if (seg.contains("~")) {
                String[] e = seg.split("~");
                Integer lo = parseMin(e[0]), hi = parseMin(e[1]);
                if (lo != null && hi != null
                        && a >= Math.min(lo, hi) && a <= Math.max(lo, hi)) return true;
            } else {
                Integer p = parseMin(seg);
                if (p != null && p == (int) a) return true;
            }
        }
        return false;
    }

    public static boolean localeMatch(String expected, String actual) {
        String a = actual;
        int h = a.indexOf('#');
        if (h >= 0) a = a.substring(0, h);   // zh_CN_#Hans_CN → zh_CN
        String country = "";
        for (String p : a.split("[_-]"))
            if (p.length() == 2 && p.equals(p.toUpperCase(Locale.ROOT))) {
                country = p; break;
            }
        for (String tok : expected.split("/")) {
            String[] p = tok.split("-");
            if (p.length > 1 && p[1].equalsIgnoreCase(country)) return true;
        }
        return false;
    }

    /** offset minutes from "UTC+5:30"/"+8"/"-3:30" fragments */
    static Integer parseMin(String s) {
        s = s.trim();
        int i = s.indexOf("TC");
        if (i >= 0) s = s.substring(i + 2);
        try {
            int sign = 1, j = 0;
            if (s.startsWith("+")) j = 1;
            else if (s.startsWith("-")) { sign = -1; j = 1; }
            String[] hm = s.substring(j).split(":");
            return sign * (Integer.parseInt(hm[0]) * 60
                    + (hm.length > 1 ? Integer.parseInt(hm[1]) : 0));
        } catch (Throwable t) { return null; }
    }

    static String nn(String s) { return s == null ? "" : s; }

    // ============ 硬件区域标记提取（跨品牌）============

    /** tag→iso：只收"精确绑定单国"的标签。开放/泛区走 OPEN，未知不判。 */
    static final String[][] TAG_ISO = {
        // Samsung CSC —— 运营商/国别绑定码（OXM 等开放码在 OPEN 集合）
        {"DCM","jp"},{"KDI","jp"},{"SBM","jp"},{"RKT","jp"},{"XJP","jp"},
        {"SKC","kr"},{"KTC","kr"},{"LUC","kr"},{"KOO","kr"},
        {"XSP","sg"},{"XME","my"},{"XID","id"},{"XXV","vn"},
        {"XTC","ph"},{"XTE","ph"},{"THL","th"},{"INS","in"},{"INU","in"},
        {"TGY","hk"},{"BRI","tw"},{"CHC","cn"},{"CHM","cn"},
        {"XSA","au"},{"TEL","au"},{"OPS","au"},{"VAU","au"},{"XNZ","nz"},
        {"XAC","ca"},{"RWC","ca"},{"BMC","ca"},{"TLA","ca"},
        {"XAA","us"},{"ATT","us"},{"TMB","us"},{"VZW","us"},
        {"SPR","us"},{"USC","us"},{"CCT","us"},
        {"TUR","tr"},{"ZTO","br"},{"ZVV","br"},{"ZTA","br"},
        // Moto/Lenovo 零售渠道（product/device 名内 retXX 前缀安全子串）
        {"RETUS","us"},{"RETCN","cn"},{"RETJP","jp"},{"RETKR","kr"},
        {"RETTW","tw"},{"RETHK","hk"},{"RETSG","sg"},{"RETMY","my"},
        {"RETID","id"},{"RETPH","ph"},{"RETTH","th"},{"RETVN","vn"},
        {"RETIN","in"},{"RETAU","au"},{"RETNZ","nz"},{"RETGB","gb"},
        {"RETDE","de"},{"RETFR","fr"},{"RETCA","ca"},{"RETMX","mx"},
        {"RETBR","br"},{"RETAR","ar"},{"RETSA","sa"},{"RETAE","ae"},
        {"RETTR","tr"},{"RETZA","za"},{"RETRU","ru"},{"RETEG","eg"},
        // 通用市场后缀（product/device/model 里出现的 _xx 标签）
        {"_CN","cn"},{"_JP","jp"},{"_KR","kr"},{"_TW","tw"},{"_HK","hk"},
        {"_SG","sg"},{"_MY","my"},{"_ID","id"},{"_PH","ph"},{"_TH","th"},
        {"_VN","vn"},{"_IN","in"},{"_AU","au"},{"_NZ","nz"},{"_GB","gb"},
        {"_DE","de"},{"_FR","fr"},{"_ES","es"},{"_IT","it"},{"_CA","ca"},
        {"_MX","mx"},{"_BR","br"},{"_AR","ar"},{"_CL","cl"},{"_AE","ae"},
        {"_SA","sa"},{"_TR","tr"},{"_ZA","za"},{"_RU","ru"},{"_EG","eg"},
        {"_UK","gb"},{"_NG","ng"},{"_PK","pk"},{"_BD","bd"},
    };
    /** 开放市场/泛区标记：兼容任何区，只显示不判定。 */
    static final Set<String> OPEN = new HashSet<>(Arrays.asList(
            "OXM","OJM","OJC","XSG","XFE","XFA","LYS","XEU","EUX","BTU",
            "DBT","EPL","XEO","VDF","VD2","SEB","SEK","SER","NEE","PHE",
            "RETEU","RETSEA","RETLA","RETAPAC","RETASIA","RETMEA","RETGL",
            "_EU","_EEA","_EUEX","_SEA","_LA","_AP","_APAC","_MEA",
            "_GLB","_GLO","_WW","_ROW","_AFR"));
    static final String[] REGION_PROPS = {
            "ro.miui.region", "persist.sys.oppo.region",
            "ro.oplus.image.region", "ro.vivo.product.region",
            "ro.product.locale.region", "ro.build.countries"};

    /** True when the region carries hardware tags, i.e. the hw/CSC row has something to compare. */
    public static boolean hasTags(String iso) {
        String[] parts = com.walter.claudewilson.model.RegionTags.split(iso, TAG_ISO);
        return !parts[0].isEmpty() || !parts[1].isEmpty();
    }

    /** Preferred Samsung CSC code for a region, from the same tag table. */
    public static String cscFor(String iso) {
        if (iso == null) return null;
        for (String[] t : TAG_ISO) {
            if (iso.equalsIgnoreCase(t[1]) && t[0].length() == 3) {
                return t[0];
            }
        }
        return null;
    }

    /** Hardware tags this region can carry, derived from the same table hwTag() matches against. */
    public static String expectedTags(String iso) {
        String[] parts = com.walter.claudewilson.model.RegionTags.split(iso, TAG_ISO);
        if (parts[0].isEmpty() && parts[1].isEmpty()) {
            return Lang.t("不限定", "unrestricted", "指定なし");
        }
        StringBuilder out = new StringBuilder(parts[0]);
        if (!parts[1].isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append(Lang.t("通用 ", "generic ")).append(parts[1]);
        }
        return out.toString();
    }

    /** {display, iso|"open"|null} — 按置信度层叠提取硬件区域标记。 */
    public static String[] hwTag() {
        String brand = nn(Build.BRAND).toLowerCase(Locale.ROOT);
        String fp = nn(Build.FINGERPRINT);
        String hay = (nn(Build.PRODUCT) + " " + nn(Build.DEVICE) + " "
                + nn(Build.MODEL)).toLowerCase(Locale.ROOT);
        // Build.* is zygote-preloaded: values freeze at zygote start, not first read.
        // Samsung：指纹 incremental "<pda>_<csc>" 精确段
        if ("samsung".equals(brand) && fp.contains(":")) {
            try {
                String inc = fp.split(":")[1].split("/")[2];
                if (inc.contains("_")) {
                    String full = inc.substring(inc.indexOf('_') + 1)
                            .toUpperCase(Locale.ROOT);
                    // CSC 版本段 = <3位码><版本>（OXM1AYC2 → OXM）
                    String csc = full.length() > 3 ? full.substring(0, 3) : full;
                    return new String[]{"csc=" + full,
                            OPEN.contains(csc) ? "open" : tagIso(csc)};
                }
            } catch (Throwable ignored) {}
        }
        // retXX / _xx 标签（裸 3 字母 CSC 码不在此扫——防 "tel"→telstra 误伤）
        for (String[] t : TAG_ISO) {
            if (!t[0].startsWith("RET") && !t[0].startsWith("_")) continue;
            String tok = t[0].toLowerCase(Locale.ROOT);
            if (hay.contains(tok))
                return new String[]{tok + "@" + brand, t[1]};
        }
        for (String o : OPEN) {
            if (o.startsWith("_") && hay.contains(o.toLowerCase(Locale.ROOT)))
                return new String[]{o.toLowerCase(Locale.ROOT) + "@" + brand,
                        "open"};
        }
        // 品牌专用 prop
        for (String k : REGION_PROPS) {
            String v = DeviceSignals.getprop(k);
            if (v != null && v.length() <= 16) {
                String u = v.trim().toUpperCase(Locale.ROOT);
                String iso = u.matches("[A-Z]{2}")
                        ? u.toLowerCase(Locale.ROOT) : null;
                return new String[]{k + "=" + u, iso == null ? "open" : iso};
            }
        }
        return new String[]{(brand.isEmpty() ? "?"
                : brand + " " + nn(Build.MODEL)) + " 无区域标记", null};
    }

    static String tagIso(String tag) {
        for (String[] t : TAG_ISO)
            if (t[0].equals(tag)) return t[1];
        return null;
    }
}
