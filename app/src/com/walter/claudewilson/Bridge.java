package com.walter.claudewilson;

/**
 * Stable app_process entry point. All commands run with the spawning shell/root
 * uid, outside the app process. Keep this class name and the CLI protocol stable.
 */
public final class Bridge {
    private Bridge() {}

    public static void main(String[] args) {
        int rc = 0;
        try {
            if (args == null || args.length == 0) {
                System.out.println("usage: Bridge <net|hw|cc|ccclear|ccget|subname|brand|simop|methods>");
                rc = 1;
            } else if ("net".equals(args[0])) {
                ShellNetworkProbe.netProbe();
            } else if ("egress".equals(args[0])) {
                ShellNetworkProbe.egressQuick();
            } else if ("guard".equals(args[0])) {
                IpGuardDaemon.runMain();
            } else if ("hw".equals(args[0])) {
                String[] t = Regions.hwTag();
                System.out.println("PRODUCT=" + android.os.Build.PRODUCT
                        + " DEVICE=" + android.os.Build.DEVICE
                        + " MODEL=" + android.os.Build.MODEL
                        + " BRAND=" + android.os.Build.BRAND
                        + " FINGERPRINT=" + android.os.Build.FINGERPRINT);
                System.out.println("hwTag=" + t[0] + " iso=" + t[1]);
            } else if ("exitinfo".equals(args[0])) {
                dumpExitInfo(args.length > 1 ? args[1] : "", args.length > 2 ? args[2] : "0");
            } else if ("cookies-scan".equals(args[0])) {
                cookiesOp(false, args.length > 1 ? args[1] : "", args.length > 2 ? args[2] : "");
            } else if ("cookies-clean".equals(args[0])) {
                cookiesOp(true, args.length > 1 ? args[1] : "", args.length > 2 ? args[2] : "");
            } else {
                // carrier_config is bound lazily inside CarrierCommands only when
                // cc / ccclear / ccget needs it, so net, hw, and subname work on
                // Wi-Fi-only devices and ROMs that restrict carrier_config.
                CarrierCommands.run(args);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            rc = 1;
        }
        // Returning from main is not enough: probes pull in connectivity/okhttp
        // internals that leave a non-daemon worker behind, and DestroyJavaVM then
        // waits on it forever. halt the VM so the shell always sees the exit.
        System.exit(rc);
    }

    /** Internal fast egress probe entry for {@link IpGuardDaemon} (keeps ShellNetworkProbe owned by Bridge). */
    static String[] probeEgressFast() {
        return ShellNetworkProbe.egressQuickRace(2600L);
    }

    /**
     * Reads Android 11+ ApplicationExitInfo processStateSummary bytes directly from
     * IActivityManager (outside target directories, zero file residue).
     */
    private static void dumpExitInfo(String pkg, String userArg) throws Exception {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        int userId = 0;
        try {
            userId = Integer.parseInt(userArg);
        } catch (NumberFormatException ignored) {
        }
        Object am = android.app.ActivityManager.class.getMethod("getService").invoke(null);
        if (am == null) {
            return;
        }
        java.lang.reflect.Method query = am.getClass().getMethod(
                "getHistoricalProcessExitReasons",
                String.class, int.class, int.class, int.class);
        Object slice = query.invoke(am, pkg, 0, 16, userId);
        if (slice == null) {
            return;
        }
        Object listObj = slice.getClass().getMethod("getList").invoke(slice);
        if (!(listObj instanceof java.util.List)) {
            return;
        }
        java.util.List<?> list = (java.util.List<?>) listObj;
        for (Object item : list) {
            if (!(item instanceof android.app.ApplicationExitInfo)) {
                continue;
            }
            android.app.ApplicationExitInfo info = (android.app.ApplicationExitInfo) item;
            byte[] state = info.getProcessStateSummary();
            if (state == null || state.length == 0) {
                continue;
            }
            String utf8 = new String(state, java.nio.charset.StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\r', ' ');
            if (utf8.startsWith("CWBG1|")) {
                System.out.println(info.getTimestamp() + "\t" + utf8);
            }
        }
    }

    /**
     * Domain-scoped inspection or deletion against a Chromium Cookies SQLite database.
     * Never reads or prints cookie values; only host_key and cookie name are inspected.
     */
    private static void cookiesOp(boolean delete, String dbPath, String domainsCsv) {
        if (dbPath == null || dbPath.isEmpty() || domainsCsv == null || domainsCsv.isEmpty()) {
            return;
        }
        java.io.File f = new java.io.File(dbPath);
        if (!f.isFile() || !f.canRead()) {
            return;
        }
        java.util.List<String> whereParts = new java.util.ArrayList<String>();
        java.util.List<String> bindArgs = new java.util.ArrayList<String>();
        for (String raw : domainsCsv.split(",")) {
            String d = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!d.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) {
                continue;
            }
            whereParts.add("(host_key = ? OR host_key LIKE ?)");
            bindArgs.add(d);
            bindArgs.add("%." + d);
        }
        if (whereParts.isEmpty()) {
            return;
        }
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < whereParts.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append(whereParts.get(i));
        }
        String[] argArr = bindArgs.toArray(new String[0]);
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            int flags = (delete ? android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                    : android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                    | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, flags);
            if (!delete) {
                int total = 0;
                android.database.Cursor c = db.rawQuery(
                        "SELECT host_key, name FROM cookies WHERE " + where
                                + " ORDER BY host_key, name", argArr);
                try {
                    while (c.moveToNext()) {
                        String host = c.getString(0);
                        String name = c.getString(1);
                        if (host != null && name != null) {
                            total++;
                            System.out.println("COOKIE\t" + host.replace('\t', ' ')
                                    + "\t" + name.replace('\t', ' '));
                        }
                    }
                } finally {
                    c.close();
                }
                System.out.println("COOKIES_TOTAL=" + total);
            } else {
                int deleted = 0;
                db.beginTransaction();
                try {
                    deleted = db.delete("cookies", where.toString(), argArr);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                int remain = 0;
                android.database.Cursor c = db.rawQuery(
                        "SELECT COUNT(*) FROM cookies WHERE " + where, argArr);
                try {
                    if (c.moveToFirst()) {
                        remain = c.getInt(0);
                    }
                } finally {
                    c.close();
                }
                System.out.println("COOKIES_DELETED=" + deleted + " COOKIES_REMAIN=" + remain);
            }
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
