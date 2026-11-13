package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.GeoTable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One region's salted persona values, keyed by space and ISO, kept in a plain file.
 *
 * <p>The first selection of a region picks a zone/city and the region's network defaults, then
 * writes them down. Later selections of the same region must read this record instead of picking
 * again; otherwise the timezone, UTC offset and location drift on every tap. Model, seed and AID
 * stay in {@link PersonaState}: they belong to the space, not to the country.
 *
 * <p>A plain file is deliberate. The rest of the app's preferences are small UI state; this
 * record must survive even when the preference store is unhealthy, and it is written through the
 * same app-files path the audit log already uses.
 */
final class RegionPersona {

    private static final String FILE = "region-personas.json";
    private static final Object LOCK = new Object();
    private static Map<String, Snapshot> cache;

    static final class Snapshot {
        final String zone;
        final String locale;
        final String op;
        final String carrier;
        final String netOp;
        final String netIso;
        final String netName;
        final double latitude;
        final double longitude;
        final String city;
        final String csc;

        Snapshot(String zone, String locale, String op, String carrier,
                 String netOp, String netIso, String netName,
                 double latitude, double longitude, String city, String csc) {
            this.zone = zone;
            this.locale = locale;
            this.op = op;
            this.carrier = carrier;
            this.netOp = netOp;
            this.netIso = netIso;
            this.netName = netName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.city = city;
            this.csc = csc;
        }

        Snapshot with(String field, String value) {
            return new Snapshot(
                    "zone".equals(field) ? value : zone,
                    "locale".equals(field) ? value : locale,
                    "op".equals(field) ? value : op,
                    "carrier".equals(field) ? value : carrier,
                    "netOp".equals(field) ? value : netOp,
                    "netIso".equals(field) ? value : netIso,
                    "netName".equals(field) ? value : netName,
                    latitude, longitude, city,
                    "csc".equals(field) ? value : csc);
        }

        Snapshot withLocation(double lat, double lng, String label) {
            return new Snapshot(zone, locale, op, carrier, netOp, netIso, netName,
                    lat, lng, label == null ? "" : label, csc);
        }
    }

    private RegionPersona() {
    }

    static Snapshot of(Context context, String user, Regions.R region) {
        if (context == null || region == null) {
            return null;
        }
        synchronized (LOCK) {
            Map<String, Snapshot> all = read(context);
            String key = key(user, region.iso);
            Snapshot saved = all.get(key);
            if (saved != null) {
                return saved;
            }
            Snapshot created = create(context, region);
            all.put(key, created);
            write(context, all);
            return created;
        }
    }

    private static Snapshot create(Context context, Regions.R region) {
        // Random only on the first selection of this region. Every later read is the snapshot.
        String[] pick = Regions.pick(region);
        String[] net = PersonaPanel.netFor(region.iso);
        java.util.List<Carrier> pool = pool(context, region);
        Carrier sim = pick(pool);
        Carrier visited = pick(pool);
        if (pool.size() > 1 && sim != null && visited != null && sim.op.equals(visited.op)) {
            for (Carrier candidate : pool) {
                if (!candidate.op.equals(sim.op)) {
                    visited = candidate;
                    break;
                }
            }
        }
        String op = sim != null ? sim.op : net != null ? net[1] : region.mcc.split("/")[0] + "01";
        String carrier = sim != null ? pretty(region, sim.name)
                : net != null ? net[2]
                : region.carriers == null || region.carriers.isEmpty()
                        ? "" : region.carriers.split("/")[0];
        String netOp = visited != null ? visited.op : op;
        String netName = visited != null ? pretty(region, visited.name) : carrier;
        GeoTable.Point point = Geo.point(context, region.iso, pick[0]);
        double lat = point == null ? 0d : point.latitude;
        double lng = point == null ? 0d : point.longitude;
        String city = point == null ? "" : point.city;
        return new Snapshot(pick[0], pick[1], op, carrier, netOp, region.iso, netName,
                lat, lng, city, Regions.cscFor(region.iso));
    }

    private static final class Carrier {
        final String op;
        final String name;

        Carrier(String op, String name) {
            this.op = op;
            this.name = name;
        }
    }

    private static final java.util.Random RNG = new java.util.Random();
    private static volatile Map<String, java.util.List<Carrier>> POOL;

    /** MCCMNC → display name pool from catalog/carriers.txt. */
    private static java.util.List<Carrier> pool(Context context, Regions.R region) {
        Map<String, java.util.List<Carrier>> all = POOL;
        if (all == null) {
            synchronized (RegionPersona.class) {
                all = POOL;
                if (all == null) {
                    all = loadPool(context);
                    POOL = all;
                }
            }
        }
        java.util.List<Carrier> out = new java.util.ArrayList<Carrier>();
        for (String mcc : region.mcc.split("/")) {
            java.util.List<Carrier> rows = all.get(mcc);
            if (rows != null) {
                out.addAll(rows);
            }
        }
        return out;
    }

    private static Map<String, java.util.List<Carrier>> loadPool(Context context) {
        Map<String, java.util.List<Carrier>> out =
                new java.util.LinkedHashMap<String, java.util.List<Carrier>>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        context.getAssets().open("catalog/carriers.txt"), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 3) {
                    continue;
                }
                String op = line.substring(0, eq).trim();
                if (!op.matches("\\d{5,6}")) {
                    continue;
                }
                String first = line.substring(eq + 1).split("\\|")[0].trim();
                String mcc = op.substring(0, 3);
                java.util.List<Carrier> rows = out.get(mcc);
                if (rows == null) {
                    rows = new java.util.ArrayList<Carrier>();
                    out.put(mcc, rows);
                }
                rows.add(new Carrier(op, first));
            }
        } catch (Throwable ignored) {
            // Missing catalog: the NET fallback below still produces a valid persona.
        }
        return out;
    }

    private static Carrier pick(java.util.List<Carrier> pool) {
        return pool.isEmpty() ? null : pool.get(RNG.nextInt(pool.size()));
    }

    private static String pretty(Regions.R region, String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return "";
        }
        if (region.carriers != null) {
            for (String name : region.carriers.split("/")) {
                if (name.equalsIgnoreCase(candidate)
                        || candidate.toLowerCase(java.util.Locale.ROOT)
                                .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    return name;
                }
            }
        }
        return Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
    }

    static void putOp(Context context, String user, Regions.R region, String op) {
        if (context == null || region == null || op == null || op.isEmpty()) {
            return;
        }
        update(context, user, region, "op", op);
    }

    static void putCarrier(Context context, String user, Regions.R region, String carrier) {
        if (context == null || region == null || carrier == null || carrier.isEmpty()) {
            return;
        }
        update(context, user, region, "carrier", carrier);
    }

    static void putLocale(Context context, String user, Regions.R region, String locale) {
        if (context == null || region == null || locale == null || locale.isEmpty()) {
            return;
        }
        update(context, user, region, "locale", locale);
    }

    static void putZone(Context context, String user, Regions.R region, String zone) {
        if (context == null || region == null || zone == null || zone.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Snapshot> all = read(context);
            String key = key(user, region.iso);
            Snapshot s = all.get(key);
            if (s == null) {
                s = create(context, region);
            }
            GeoTable.Point point = Geo.point(context, region.iso, zone);
            all.put(key, s.with("zone", zone).withLocation(
                    point == null ? 0d : point.latitude,
                    point == null ? 0d : point.longitude,
                    point == null ? "" : point.city));
            write(context, all);
        }
    }

    static void putLocation(Context context, String user, Regions.R region,
                            double latitude, double longitude, String city) {
        if (context == null || region == null) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Snapshot> all = read(context);
            String key = key(user, region.iso);
            Snapshot s = all.get(key);
            if (s == null) {
                s = create(context, region);
            }
            all.put(key, s.withLocation(latitude, longitude, city));
            write(context, all);
        }
    }

    static void putCsc(Context context, String user, Regions.R region, String csc) {
        if (context == null || region == null || csc == null || csc.isEmpty()) {
            return;
        }
        update(context, user, region, "csc", csc);
    }

    private static void update(Context context, String user, Regions.R region,
                               String field, String value) {
        synchronized (LOCK) {
            Map<String, Snapshot> all = read(context);
            String key = key(user, region.iso);
            Snapshot s = all.get(key);
            if (s == null) {
                s = create(context, region);
            }
            all.put(key, s.with(field, value));
            write(context, all);
        }
    }

    private static Map<String, Snapshot> read(Context context) {
        if (cache != null) {
            return cache;
        }
        Map<String, Snapshot> all = new LinkedHashMap<String, Snapshot>();
        File file = file(context);
        if (!file.isFile()) {
            cache = all;
            return all;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.max(0, file.length())];
            int n = in.read(data);
            if (n > 0) {
                JSONObject root = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject o = root.optJSONObject(key);
                    if (o == null) {
                        continue;
                    }
                    all.put(key, new Snapshot(
                            o.optString("zone", ""),
                            o.optString("locale", ""),
                            o.optString("op", ""),
                            o.optString("carrier", ""),
                            o.optString("netOp", ""),
                            o.optString("netIso", ""),
                            o.optString("netName", ""),
                            o.optDouble("lat", 0d),
                            o.optDouble("lng", 0d),
                            o.optString("city", ""),
                            o.optString("csc", "")));
                }
            }
        } catch (Throwable ignored) {
            // A corrupt record is discarded; the next selection writes a fresh one.
        }
        cache = all;
        return all;
    }

    private static void write(Context context, Map<String, Snapshot> all) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Snapshot> e : all.entrySet()) {
                Snapshot s = e.getValue();
                JSONObject o = new JSONObject();
                o.put("zone", s.zone);
                o.put("locale", s.locale);
                o.put("op", s.op);
                o.put("carrier", s.carrier);
                o.put("netOp", s.netOp);
                o.put("netIso", s.netIso);
                o.put("netName", s.netName);
                o.put("lat", s.latitude);
                o.put("lng", s.longitude);
                o.put("city", s.city);
                o.put("csc", s.csc);
                root.put(e.getKey(), o);
            }
            try (FileOutputStream out = new FileOutputStream(file(context))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        } catch (Throwable ignored) {
            // Keep the in-memory snapshot; the next update retries the file.
        }
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private static String key(String user, String iso) {
        return (user == null ? "0" : user) + "|"
                + (iso == null ? "" : iso.toLowerCase(java.util.Locale.ROOT));
    }
}
