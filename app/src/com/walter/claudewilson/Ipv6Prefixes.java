package com.walter.claudewilson;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * ASN-indexed IPv6 prefix database with broadband vs mobile role separation
 * and prefix-capacity weighting.
 */
public final class Ipv6Prefixes {

    private static volatile CountryDb CACHE;

    private static final Map<String, String[]> CARRIER_ALIASES = buildCarrierAliases();

    private Ipv6Prefixes() {}

    /** Picks a WiFi / default prefix aligned with egress ASN or country broadband ISP pool. */
    public static String pickPrefix(Context ctx, String iso, long seed, String egressAsn) {
        if (iso == null || iso.isEmpty()) return null;
        CountryDb db = load(ctx);
        CountryEntry entry = db.countries.get(iso.toLowerCase(Locale.ROOT));
        if (entry == null) return null;

        Random rng = new Random(seed);

        // Priority 1: exact non-hosting ASN match
        if (egressAsn != null && !egressAsn.isEmpty()) {
            String clean = egressAsn.trim().toUpperCase(Locale.ROOT);
            String asn = clean.startsWith("AS") ? clean.substring(2) : clean;
            String cat = entry.asnCategories.get(asn);
            List<String> asnPrefixes = entry.byAsn.get(asn);
            if (asnPrefixes != null && !asnPrefixes.isEmpty()
                    && !"hosting".equalsIgnoreCase(cat)) {
                return pickWeightedPrefix(asnPrefixes, rng);
            }
        }

        // Priority 2: user-weighted broadband ISP pool (falls back to all ISP ASNs)
        List<String> pool = !entry.broadbandAsns.isEmpty() ? entry.broadbandAsns : entry.ispAsns;
        String ispPick = entry.pickFromPool(pool, rng, null);
        if (ispPick != null) return ispPick;

        // Priority 3: flat fallback pool
        if (!entry.fallback.isEmpty()) {
            return pickWeightedPrefix(entry.fallback, rng);
        }
        return null;
    }

    /** Backward-compatible overload without egress ASN. */
    public static String pickPrefix(Context ctx, String iso, long seed) {
        return pickPrefix(ctx, iso, seed, null);
    }

    /**
     * Picks a cellular prefix aligned with the persona's SIM carrier or country MNO pool.
     * Prefers a prefix distinct from {@code wifiPrefix} whenever alternatives exist.
     */
    public static String pickMobilePrefix(Context ctx, String iso, String simCarrier,
                                          String wifiPrefix, long seed) {
        if (iso == null || iso.isEmpty()) return wifiPrefix;
        CountryDb db = load(ctx);
        CountryEntry entry = db.countries.get(iso.toLowerCase(Locale.ROOT));
        if (entry == null) return wifiPrefix;

        Random rng = new Random(seed ^ 0x5A17C3D9E24B810FL);

        // Priority 1: match SIM carrier name against country ASNs
        List<String> matchedAsns = entry.matchCarrierAsns(simCarrier);
        if (!matchedAsns.isEmpty()) {
            String pick = entry.pickFromPool(matchedAsns, rng, wifiPrefix);
            if (pick != null) return pick;
        }

        // Priority 2: user-weighted mobile MNO pool
        if (!entry.mobileAsns.isEmpty()) {
            String pick = entry.pickFromPool(entry.mobileAsns, rng, wifiPrefix);
            if (pick != null) return pick;
        }

        // Priority 3: general ISP pool, then fallback
        String ispPick = entry.pickFromPool(entry.ispAsns, rng, wifiPrefix);
        if (ispPick != null) return ispPick;

        if (!entry.fallback.isEmpty()) {
            return pickDistinctPrefix(entry.fallback, rng, wifiPrefix);
        }
        return wifiPrefix;
    }

    /** Returns all known ASN entries for a country (for diagnostics). */
    public static Map<String, String> countryAsns(Context ctx, String iso) {
        if (iso == null) return Collections.emptyMap();
        CountryDb db = load(ctx);
        CountryEntry entry = db.countries.get(iso.toLowerCase(Locale.ROOT));
        if (entry == null) return Collections.emptyMap();
        return entry.asnNames;
    }

    // ---- capacity weighting ----

    static String pickDistinctPrefix(List<String> prefixes, Random rng, String avoid) {
        if (prefixes == null || prefixes.isEmpty()) return null;
        String chosen = pickWeightedPrefix(prefixes, rng);
        if (avoid == null || avoid.isEmpty() || prefixes.size() <= 1 || !avoid.equals(chosen)) {
            return chosen;
        }
        List<String> filtered = new ArrayList<>(prefixes.size());
        for (String p : prefixes) {
            if (!avoid.equals(p)) filtered.add(p);
        }
        return filtered.isEmpty() ? chosen : pickWeightedPrefix(filtered, rng);
    }

    static String pickWeightedPrefix(List<String> prefixes, Random rng) {
        if (prefixes == null || prefixes.isEmpty()) return null;
        if (prefixes.size() == 1) return prefixes.get(0);
        long total = 0L;
        long[] weights = new long[prefixes.size()];
        for (int i = 0; i < prefixes.size(); i++) {
            long w = prefixWeight(prefixes.get(i));
            weights[i] = w;
            total += w;
        }
        if (total <= 0L) {
            return prefixes.get(rng.nextInt(prefixes.size()));
        }
        long target = (long) (rng.nextDouble() * total);
        long acc = 0L;
        for (int i = 0; i < prefixes.size(); i++) {
            acc += weights[i];
            if (acc > target) return prefixes.get(i);
        }
        return prefixes.get(prefixes.size() - 1);
    }

    /**
     * True subscriber-capacity weighting across /16..48:
     * /19 -> 2^29, /20 -> 2^28, /29 -> 2^19, /32 -> 2^16, /40 -> 2^8, /48 -> 1.
     */
    private static long prefixWeight(String cidr) {
        if (cidr == null) return 1L;
        int slash = cidr.indexOf('/');
        if (slash < 0) return 1L;
        try {
            int len = Integer.parseInt(cidr.substring(slash + 1).trim());
            int clamped = Math.max(16, Math.min(48, len));
            return 1L << (48 - clamped);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private static String foldAscii(String s) {
        if (s == null || s.isEmpty()) return "";
        String norm = Normalizer.normalize(s, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    // ---- data model ----

    private static final class CountryDb {
        final Map<String, CountryEntry> countries;
        CountryDb(Map<String, CountryEntry> countries) { this.countries = countries; }
    }

    private static final class CountryEntry {
        final Map<String, List<String>> byAsn;
        final Map<String, String> asnNames;
        final Map<String, String> asnCategories;
        final Map<String, Long> asnUsers;
        final Set<String> mobileSet;
        final List<String> ispAsns;
        final List<String> broadbandAsns;
        final List<String> mobileAsns;
        final List<String> fallback;

        CountryEntry(Map<String, List<String>> byAsn,
                     Map<String, String> asnNames,
                     Map<String, String> asnCategories,
                     Map<String, Long> asnUsers,
                     Set<String> mobileSet,
                     List<String> ispAsns,
                     List<String> broadbandAsns,
                     List<String> mobileAsns,
                     List<String> fallback) {
            this.byAsn = byAsn;
            this.asnNames = asnNames;
            this.asnCategories = asnCategories;
            this.asnUsers = asnUsers;
            this.mobileSet = mobileSet;
            this.ispAsns = ispAsns;
            this.broadbandAsns = broadbandAsns;
            this.mobileAsns = mobileAsns;
            this.fallback = fallback;
        }

        String pickFromPool(List<String> pool, Random rng, String avoidPrefix) {
            if (pool == null || pool.isEmpty()) return null;
            for (int attempt = 0; attempt < 4; attempt++) {
                String asn = pickWeightedAsn(pool, rng);
                List<String> prefixes = byAsn.get(asn);
                if (prefixes == null || prefixes.isEmpty()) continue;
                String candidate = pickDistinctPrefix(prefixes, rng, avoidPrefix);
                if (candidate == null) continue;
                if (avoidPrefix == null || !avoidPrefix.equals(candidate) || attempt == 3) {
                    return candidate;
                }
            }
            return null;
        }

        private String pickWeightedAsn(List<String> pool, Random rng) {
            if (pool.size() == 1) return pool.get(0);
            long totalUsers = 0L;
            for (String asn : pool) {
                Long u = asnUsers.get(asn);
                totalUsers += Math.max(u != null ? u : 1L, 1L);
            }
            long target = (long) (rng.nextDouble() * totalUsers);
            long cumulative = 0L;
            for (String asn : pool) {
                Long u = asnUsers.get(asn);
                cumulative += Math.max(u != null ? u : 1L, 1L);
                if (cumulative > target) return asn;
            }
            return pool.get(0);
        }

        List<String> matchCarrierAsns(String simCarrier) {
            if (simCarrier == null) return Collections.emptyList();
            String rawTrim = simCarrier.trim();
            if (rawTrim.isEmpty() || "-".equals(rawTrim) || "\u2014".equals(rawTrim)) {
                return Collections.emptyList();
            }
            String rawLower = rawTrim.toLowerCase(Locale.ROOT);
            String folded = foldAscii(rawTrim);
            String compact = folded.replaceAll("[^a-z0-9+]", "");

            List<String> needles = new ArrayList<>();
            String[] rawAliases = CARRIER_ALIASES.get(rawLower);
            if (rawAliases != null) Collections.addAll(needles, rawAliases);
            String[] compactAliases = CARRIER_ALIASES.get(compact);
            if (compactAliases != null) Collections.addAll(needles, compactAliases);
            if (folded.length() >= 2) needles.add(folded);
            if (compact.length() >= 2 && !compact.equals(folded)) needles.add(compact);
            if (needles.isEmpty()) return Collections.emptyList();

            List<String> allMatched = new ArrayList<>();
            List<String> mobileMatched = new ArrayList<>();
            for (String asn : ispAsns) {
                String name = asnNames.get(asn);
                if (name == null || name.isEmpty()) continue;
                String lower = foldAscii(name);
                for (String needle : needles) {
                    if (needle == null || needle.length() < 2) continue;
                    if (containsWord(lower, needle)) {
                        allMatched.add(asn);
                        if (mobileSet.contains(asn)) {
                            mobileMatched.add(asn);
                        }
                        break;
                    }
                }
            }
            return !mobileMatched.isEmpty() ? mobileMatched : allMatched;
        }

        private static boolean containsWord(String text, String word) {
            int idx = text.indexOf(word);
            while (idx >= 0) {
                boolean leftOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
                int end = idx + word.length();
                boolean rightOk = end >= text.length()
                        || !Character.isLetterOrDigit(text.charAt(end));
                if (leftOk && rightOk) return true;
                idx = text.indexOf(word, idx + 1);
            }
            return false;
        }
    }

    private static Map<String, String[]> buildCarrierAliases() {
        Map<String, String[]> m = new HashMap<>();
        m.put("docomo", new String[]{"docomo"});
        m.put("nttdocomo", new String[]{"docomo"});
        m.put("au", new String[]{"kddi"});
        m.put("kddi", new String[]{"kddi"});
        m.put("softbank", new String[]{"softbank"});
        m.put("rakuten", new String[]{"rakuten"});
        m.put("skt", new String[]{"sk telecom"});
        m.put("sktelecom", new String[]{"sk telecom"});
        m.put("kt", new String[]{"korea telecom"});
        m.put("lgu+", new String[]{"lgtelecom", "lg uplus"});
        m.put("lguplus", new String[]{"lgtelecom", "lg uplus"});
        m.put("ee", new String[]{"ee limited", "british telecommunications"});
        m.put("o2", new String[]{"o2", "telefonica"});
        m.put("o2uk", new String[]{"o2", "telefonica"});
        m.put("o2de", new String[]{"o2", "telefonica"});
        m.put("movistar", new String[]{"telefonica", "movistar"});
        m.put("vivo", new String[]{"telefonica", "vivo"});
        m.put("three", new String[]{"hutchison", "three"});
        m.put("3", new String[]{"hutchison"});
        m.put("telekom", new String[]{"deutsche telekom", "telekom"});
        m.put("turktelekom", new String[]{"turk telekomunikasyon", "turk telekom"});
        m.put("tmobile", new String[]{"t-mobile", "deutsche telekom"});
        m.put("att", new String[]{"at&t"});
        m.put("verizon", new String[]{"verizon", "cellco"});
        m.put("singtel", new String[]{"singapore telecom", "singtel"});
        m.put("m1", new String[]{"m1"});
        m.put("a1", new String[]{"a1"});
        m.put("xl", new String[]{"xl axiata", "xl"});
        m.put("go", new String[]{"go p.l.c", "go"});
        m.put("telkomsel", new String[]{"telekomunikasi selular", "telkomsel"});
        m.put("tim", new String[]{"telecom italia", "tim"});
        m.put("ais", new String[]{"advanced wireless"});
        m.put("dtac", new String[]{"total access", "dtac"});
        m.put("true", new String[]{"true internet", "true move"});
        m.put("truemove", new String[]{"true internet", "true move"});
        m.put("truemoveh", new String[]{"true internet", "true move"});
        m.put("onenz", new String[]{"vodafone", "spark"});
        m.put("2degrees", new String[]{"two degrees", "vocus"});
        m.put("chunghwa", new String[]{"chunghwa"});
        m.put("cht", new String[]{"chunghwa"});
        m.put("\u4e2d\u83ef\u96fb\u4fe1", new String[]{"chunghwa"});
        m.put("\u4e2d\u534e\u7535\u4fe1", new String[]{"chunghwa"});
        m.put("taiwanmobile", new String[]{"taiwan mobile"});
        m.put("twm", new String[]{"taiwan mobile"});
        m.put("\u53f0\u54e5\u5927", new String[]{"taiwan mobile"});
        m.put("\u53f0\u6e7e\u5927\u54e5\u5927", new String[]{"taiwan mobile"});
        m.put("fet", new String[]{"far eastone", "far easttone"});
        m.put("fareastone", new String[]{"far eastone", "far easttone"});
        m.put("\u8fdc\u4f20", new String[]{"far eastone", "far easttone"});
        m.put("\u9060\u50b3", new String[]{"far eastone", "far easttone"});
        m.put("smart", new String[]{"smart communications", "pldt"});
        m.put("globe", new String[]{"globe telecom"});
        m.put("mobicom", new String[]{"mobinet", "mobicom"});
        m.put("du", new String[]{"emirates integrated", "du"});
        m.put("etisalat", new String[]{"emirates telecommunications", "etisalat"});
        m.put("eetisalat", new String[]{"emirates telecommunications", "etisalat"});
        m.put("stc", new String[]{"saudi telecom", "stc"});
        m.put("telcel", new String[]{"uninet", "america movil", "telcel"});
        m.put("claro", new String[]{"claro", "america movil", "comcel"});
        return m;
    }

    // ---- loader ----

    private static CountryDb load(Context ctx) {
        CountryDb cached = CACHE;
        if (cached != null) return cached;
        synchronized (Ipv6Prefixes.class) {
            if (CACHE != null) return CACHE;
            Map<String, CountryEntry> countries = new HashMap<>();
            try (InputStream is = ctx.getAssets().open("ipv6-prefixes.json")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(163840);
                byte[] chunk = new byte[8192];
                int r;
                while ((r = is.read(chunk)) >= 0) {
                    bos.write(chunk, 0, r);
                }
                JSONObject root = new JSONObject(
                        new String(bos.toByteArray(), StandardCharsets.UTF_8));
                Iterator<String> ccKeys = root.keys();
                while (ccKeys.hasNext()) {
                    String cc = ccKeys.next();
                    Object val = root.get(cc);
                    if (val instanceof JSONObject) {
                        countries.put(cc.toLowerCase(Locale.ROOT),
                                parseCountry((JSONObject) val));
                    } else if (val instanceof JSONArray) {
                        countries.put(cc.toLowerCase(Locale.ROOT),
                                parseFlatArray((JSONArray) val));
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("ClaudeWilson", "ipv6-prefixes: " + e);
            }
            CountryDb db = new CountryDb(Collections.unmodifiableMap(countries));
            CACHE = db;
            return db;
        }
    }

    private static CountryEntry parseCountry(JSONObject obj) throws Exception {
        Map<String, List<String>> byAsn = new HashMap<>();
        Map<String, String> asnNames = new HashMap<>();
        Map<String, String> asnCategories = new HashMap<>();
        Map<String, Long> asnUsers = new HashMap<>();
        Set<String> mobileSet = new HashSet<>();
        List<String> ispAsns = new ArrayList<>();
        List<String> broadbandAsns = new ArrayList<>();
        List<String> mobileAsns = new ArrayList<>();
        List<String> fallback = Collections.emptyList();

        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("_fallback".equals(key)) {
                JSONArray arr = obj.getJSONArray(key);
                fallback = toList(arr);
                continue;
            }
            Object v = obj.get(key);
            if (v instanceof JSONObject) {
                JSONObject asnObj = (JSONObject) v;
                String name = asnObj.optString("name", "");
                String category = asnObj.optString("category", "");
                long users = asnObj.optLong("users", 0L);
                boolean mobile = asnObj.optBoolean("mobile", false);
                boolean isIsp = "isp".equals(category);
                boolean broadband = asnObj.optBoolean("broadband", isIsp);
                asnNames.put(key, name);
                asnCategories.put(key, category);
                asnUsers.put(key, users);
                JSONArray prefArr = asnObj.optJSONArray("prefixes");
                if (prefArr != null) {
                    List<String> prefixes = toList(prefArr);
                    if (!prefixes.isEmpty()) {
                        byAsn.put(key, Collections.unmodifiableList(prefixes));
                        if (isIsp || mobile) {
                            ispAsns.add(key);
                            if (broadband && isIsp) broadbandAsns.add(key);
                            if (mobile) {
                                mobileAsns.add(key);
                                mobileSet.add(key);
                            }
                        }
                    }
                }
            }
        }
        return new CountryEntry(
                Collections.unmodifiableMap(byAsn),
                Collections.unmodifiableMap(asnNames),
                Collections.unmodifiableMap(asnCategories),
                Collections.unmodifiableMap(asnUsers),
                Collections.unmodifiableSet(mobileSet),
                Collections.unmodifiableList(ispAsns),
                Collections.unmodifiableList(broadbandAsns),
                Collections.unmodifiableList(mobileAsns),
                Collections.unmodifiableList(fallback));
    }

    private static CountryEntry parseFlatArray(JSONArray arr) throws Exception {
        List<String> list = toList(arr);
        return new CountryEntry(
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptySet(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.unmodifiableList(list));
    }

    private static List<String> toList(JSONArray arr) throws Exception {
        List<String> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.getString(i));
        }
        return list;
    }
}
