package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inspects and wipes on-device target data across all installed users:
 * <ol>
 *   <li>Private sandbox: CE ({@code /data/user/$U/$PKG}), DE ({@code /data/user_de/$U/$PKG}),
 *       Keystore2 namespace (reset by {@code pm clear}), external app dirs, and expired
 *       {@code /data/local/tmp/claudewilson/backup/$PKG} files.</li>
 *   <li>Optional domain-scoped Chromium browser session cookies (via {@link Bridge}
 *       {@code cookies-clean}, strictly limited to {@link TargetSpec#cookieDomains()}).</li>
 *   <li>Process RAM stop + {@code netd} DNS cache flush.</li>
 * </ol>
 *
 * <p>GMS, Play, and non-target browser sites are never wiped.
 */
final class DataClean {
    private static final String[] CHROMIUM_BROWSERS = {
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.microsoft.emmx"
    };

    private DataClean() {}

    static final class CookieHit {
        final String host;
        final String name;

        CookieHit(String host, String name) {
            this.host = host;
            this.name = name;
        }
    }

    static final class Snapshot {
        final String pkg;
        final String displayName;
        final boolean scanned;
        final boolean rootMode;
        final List<Integer> users;
        final long ceBytes;
        final long deBytes;
        final long extBytes;
        final int extFiles;
        final int legacyResidueCount;
        final int cwBackupCount;
        final boolean browserChecked;
        final String browserPkg;
        final List<CookieHit> browserCookies;
        final boolean chromeRunning;
        final boolean targetRunning;
        final int accountAuthCount;
        final boolean hasBlockStoreMarker;
        final boolean allowBackup;
        final boolean backupManagerEnabled;
        final boolean ssaidHooked;

        Snapshot(String pkg, String displayName, boolean scanned, boolean rootMode,
                 List<Integer> users, long ceBytes, long deBytes, long extBytes, int extFiles,
                 int legacyResidueCount, int cwBackupCount, boolean browserChecked,
                 String browserPkg, List<CookieHit> browserCookies, boolean chromeRunning,
                 boolean targetRunning, int accountAuthCount, boolean hasBlockStoreMarker,
                 boolean allowBackup, boolean backupManagerEnabled, boolean ssaidHooked) {
            this.pkg = pkg;
            this.displayName = displayName;
            this.scanned = scanned;
            this.rootMode = rootMode;
            this.users = Collections.unmodifiableList(users);
            this.ceBytes = Math.max(0L, ceBytes);
            this.deBytes = Math.max(0L, deBytes);
            this.extBytes = Math.max(0L, extBytes);
            this.extFiles = Math.max(0, extFiles);
            this.legacyResidueCount = Math.max(0, legacyResidueCount);
            this.cwBackupCount = Math.max(0, cwBackupCount);
            this.browserChecked = browserChecked;
            this.browserPkg = browserPkg == null ? "" : browserPkg;
            this.browserCookies = Collections.unmodifiableList(browserCookies);
            this.chromeRunning = chromeRunning;
            this.targetRunning = targetRunning;
            this.accountAuthCount = Math.max(0, accountAuthCount);
            this.hasBlockStoreMarker = hasBlockStoreMarker;
            this.allowBackup = allowBackup;
            this.backupManagerEnabled = backupManagerEnabled;
            this.ssaidHooked = ssaidHooked;
        }

        long totalSandboxBytes() {
            return ceBytes + deBytes + extBytes;
        }

        boolean hasSandboxData() {
            return ceBytes > 4096L || deBytes > 4096L || extFiles > 0
                    || legacyResidueCount > 0 || cwBackupCount > 0;
        }

        int cookieCount() {
            return browserCookies.size();
        }

        int boundaryCount() {
            int n = 0;
            if (accountAuthCount > 0) n++;
            if (hasBlockStoreMarker) n++;
            if (!ssaidHooked) n++;
            if (allowBackup && backupManagerEnabled) n++;
            return n;
        }

        boolean isClean() {
            return scanned && !hasSandboxData() && cookieCount() == 0 && !targetRunning;
        }
    }

    static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%d KB", Math.max(1L, bytes / 1024L));
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Deep read-only inspection of sandbox, external residue, browser cookies, and boundaries. */
    static Snapshot inspect(Context context, TargetSpec target, boolean ssaidHooked) {
        String pkg = target.packageName();
        boolean root = RootShell.rootReady();
        if (!ShellQuote.packageName(pkg) || !RootShell.shellReady()) {
            return new Snapshot(pkg, target.displayName(), false, root,
                    Collections.<Integer>emptyList(), 0L, 0L, 0L, 0, 0, 0,
                    false, "", Collections.<CookieHit>emptyList(), false,
                    false, 0, false, false, false, ssaidHooked);
        }

        String selfPkg = context == null ? "" : context.getApplicationContext().getPackageName();
        String domainsCsv = joinDomains(target.cookieDomains());

        StringBuilder b = new StringBuilder();
        b.append("R=/proc/1/root; [ -d \"$R/data/user\" ] || R=\"\"; ");
        b.append("USERS=$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); ");
        b.append("CE=0; DE=0; EXT=0; EXTF=0; LEG=0; BLK=0; ");
        b.append("for U in $USERS; do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(pkg).append("$\" || continue; ");
        b.append("echo \"USER $U\"; ");
        if (root) {
            b.append("C=$(du -sk \"$R/data/user/$U/").append(pkg).append("\" 2>/dev/null | awk '{print $1}'); [ -n \"$C\" ] && CE=$((CE + C * 1024)); ");
            b.append("D=$(du -sk \"$R/data/user_de/$U/").append(pkg).append("\" 2>/dev/null | awk '{print $1}'); [ -n \"$D\" ] && DE=$((DE + D * 1024)); ");
            b.append("if ls \"$R/data/user/$U/").append(pkg).append("/no_backup/\"*block_store* >/dev/null 2>&1; then BLK=1; fi; ");
        }
        b.append("for EP in /storage/emulated/$U/Android/data/").append(pkg)
                .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                .append(" /storage/emulated/$U/Android/media/").append(pkg).append("; do ");
        b.append("if [ -e \"$EP\" ]; then ");
        b.append("E=$(du -sk \"$EP\" 2>/dev/null | awk '{print $1}'); [ -n \"$E\" ] && EXT=$((EXT + E * 1024)); ");
        b.append("F=$(find \"$EP\" -type f 2>/dev/null | wc -l); EXTF=$((EXTF + F)); ");
        b.append("L=$(find \"$EP\" -type f -name 'cw-hookcheck*.txt' 2>/dev/null | wc -l); LEG=$((LEG + L)); ");
        b.append("fi; done; ");
        b.append("done; ");

        b.append("BAK=0; ");
        b.append("if [ -d /data/local/tmp/claudewilson/backup/").append(pkg).append(" ]; then ");
        b.append("BAK=$(find /data/local/tmp/claudewilson/backup/").append(pkg).append(" -type f 2>/dev/null | wc -l); fi; ");

        b.append("RUN=0; pidof ").append(pkg).append(" >/dev/null 2>&1 && RUN=1; ");
        b.append("CRUN=0; pidof com.android.chrome >/dev/null 2>&1 && CRUN=1; ");
        b.append("ABK=0; dumpsys package ").append(pkg).append(" 2>/dev/null | grep -q 'ALLOW_BACKUP' && ABK=1; ");
        b.append("BMGR=0; bmgr enabled 2>/dev/null | grep -qi 'enabled$' && BMGR=1; ");
        b.append("ACCT=$(dumpsys account 2>/dev/null | grep -c '").append(pkg).append(",'); ");
        b.append("echo \"STATS ce=$CE de=$DE ext=$EXT extf=$EXTF leg=$LEG bak=$BAK run=$RUN crun=$CRUN blk=$BLK abk=$ABK bmgr=$BMGR acct=$ACCT\"; ");

        if (root && !domainsCsv.isEmpty() && ShellQuote.packageName(selfPkg)) {
            b.append("NS=\"\"; command -v nsenter >/dev/null 2>&1 && NS=\"nsenter -t 1 -m --\"; ");
            b.append("APK=$(pm path ").append(selfPkg).append(" 2>/dev/null | head -n 1 | cut -d: -f2); ");
            b.append("if [ -n \"$APK\" ]; then ");
            b.append("for B in ");
            for (int i = 0; i < CHROMIUM_BROWSERS.length; i++) {
                if (i > 0) b.append(' ');
                b.append(CHROMIUM_BROWSERS[i]);
            }
            b.append("; do ");
            b.append("DB_CHK=\"$R/data/user/0/$B/app_chrome/Default/Cookies\"; ");
            b.append("DB_REAL=\"/data/user/0/$B/app_chrome/Default/Cookies\"; ");
            b.append("if [ -f \"$DB_CHK\" ]; then ");
            b.append("echo \"BROWSER_DB $B\"; ");
            b.append("$NS app_process -Djava.class.path=$APK /system/bin com.walter.claudewilson.Bridge cookies-scan \"$DB_REAL\" '")
                    .append(domainsCsv).append("' 2>/dev/null; ");
            b.append("break; fi; done; fi; ");
        }
        b.append("echo DONE");

        String out = RootShell.run(b.toString(), 32768, 12000L);
        return parseSnapshot(pkg, target.displayName(), root, ssaidHooked, out);
    }

    private static Snapshot parseSnapshot(String pkg, String displayName, boolean root,
                                          boolean ssaidHooked, String out) {
        if (out == null || !out.contains("DONE")) {
            return new Snapshot(pkg, displayName, false, root, Collections.<Integer>emptyList(),
                    0L, 0L, 0L, 0, 0, 0, false, "", Collections.<CookieHit>emptyList(),
                    false, false, 0, false, false, false, ssaidHooked);
        }
        Set<Integer> userSet = new LinkedHashSet<Integer>();
        long ce = 0L, de = 0L, ext = 0L;
        int extf = 0, leg = 0, bak = 0, acct = 0;
        boolean run = false, crun = false, blk = false, abk = false, bmgr = false;
        boolean browserChecked = false;
        String browserPkg = "";
        List<CookieHit> cookies = new ArrayList<CookieHit>();

        for (String rawLine : out.split("\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("USER ")) {
                try {
                    userSet.add(Integer.parseInt(line.substring(5).trim()));
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("STATS ")) {
                for (String tok : line.substring(6).split(" ")) {
                    int eq = tok.indexOf('=');
                    if (eq <= 0) continue;
                    String k = tok.substring(0, eq);
                    String v = tok.substring(eq + 1);
                    if ("ce".equals(k)) ce = parseLong(v);
                    else if ("de".equals(k)) de = parseLong(v);
                    else if ("ext".equals(k)) ext = parseLong(v);
                    else if ("extf".equals(k)) extf = (int) parseLong(v);
                    else if ("leg".equals(k)) leg = (int) parseLong(v);
                    else if ("bak".equals(k)) bak = (int) parseLong(v);
                    else if ("run".equals(k)) run = "1".equals(v);
                    else if ("crun".equals(k)) crun = "1".equals(v);
                    else if ("blk".equals(k)) blk = "1".equals(v);
                    else if ("abk".equals(k)) abk = "1".equals(v);
                    else if ("bmgr".equals(k)) bmgr = "1".equals(v);
                    else if ("acct".equals(k)) acct = (int) parseLong(v);
                }
            } else if (line.startsWith("BROWSER_DB ")) {
                browserChecked = true;
                browserPkg = line.substring(11).trim();
            } else if (line.startsWith("COOKIE\t")) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    cookies.add(new CookieHit(parts[1], parts[2]));
                }
            }
        }
        return new Snapshot(pkg, displayName, true, root, new ArrayList<Integer>(userSet),
                ce, de, ext, extf, leg, bak, browserChecked, browserPkg, cookies,
                crun, run, acct, blk, abk, bmgr, ssaidHooked);
    }

    /** Full scoped cleanup script: multi-user sandbox + external + backup + netd DNS + optional domain cookies. */
    static String cleanCommand(Context context, TargetSpec target, boolean cleanBrowser) {
        String pkg = target.packageName();
        boolean root = RootShell.rootReady();
        String selfPkg = context == null ? "" : context.getApplicationContext().getPackageName();
        String domainsCsv = joinDomains(target.cookieDomains());

        StringBuilder b = new StringBuilder();
        b.append("R=/proc/1/root; [ -d \"$R/data/user\" ] || R=\"\"; ");
        b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(pkg).append("$\" || continue; ");
        b.append("am force-stop --user \"$U\" ").append(pkg).append(" >/dev/null 2>&1; ");
        b.append("CO=$(pm clear --user \"$U\" ").append(pkg).append(" 2>&1); RC=$?; ");
        b.append("case \"$CO\" in *Success*) ;; *) RC=1 ;; esac; ");
        b.append("echo \"CLEAR pkg=").append(pkg).append(" user=$U rc=$RC out=$(printf '%s' \"$CO\" | head -n 1 | tr ' ' '_')\"; ");
        b.append("am force-stop --user \"$U\" ").append(pkg).append(" >/dev/null 2>&1; ");
        b.append("for P in /storage/emulated/$U/Android/data/").append(pkg)
                .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                .append(" /storage/emulated/$U/Android/media/").append(pkg)
                .append(" /sdcard/Android/data/").append(pkg).append("; do rm -rf \"$P\"; done; ");
        if (root) {
            b.append("CE=$(find \"$R/data/user/$U/").append(pkg)
                    .append("\" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
            b.append("DE=$(find \"$R/data/user_de/$U/").append(pkg)
                    .append("\" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
        } else {
            b.append("CE=0; DE=0; [ \"$RC\" -eq 0 ] || CE=1; ");
        }
        b.append("EX=$(find /storage/emulated/$U/Android/data/").append(pkg)
                .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                .append(" /storage/emulated/$U/Android/media/").append(pkg)
                .append(" -mindepth 1 2>/dev/null | wc -l); ");
        b.append("echo \"INTERNAL user=$U ce=$CE de=$DE ext=$EX\"; ");
        b.append("done; ");

        // Remove expired TargetDataEdit backup files for this target
        b.append("rm -rf /data/local/tmp/claudewilson/backup/").append(pkg).append(" 2>/dev/null || true; ");

        // Flush netd DNS RAM cache when root is active
        if (root) {
            b.append("ndc resolver flushdefaultif >/dev/null 2>&1 || true; ");
        }

        // Domain-scoped browser cookie cleanup (only when enabled, root active, and domains configured)
        if (cleanBrowser && root && !domainsCsv.isEmpty() && ShellQuote.packageName(selfPkg)) {
            b.append("NS=\"\"; command -v nsenter >/dev/null 2>&1 && NS=\"nsenter -t 1 -m --\"; ");
            b.append("APK=$(pm path ").append(selfPkg).append(" 2>/dev/null | head -n 1 | cut -d: -f2); ");
            b.append("if [ -n \"$APK\" ]; then ");
            b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
            b.append("for B in ");
            for (int i = 0; i < CHROMIUM_BROWSERS.length; i++) {
                if (i > 0) b.append(' ');
                b.append(CHROMIUM_BROWSERS[i]);
            }
            b.append("; do ");
            b.append("DB_CHK=\"$R/data/user/$U/$B/app_chrome/Default/Cookies\"; ");
            b.append("DB_REAL=\"/data/user/$U/$B/app_chrome/Default/Cookies\"; ");
            b.append("[ -f \"$DB_CHK\" ] || continue; ");
            b.append("am force-stop --user \"$U\" \"$B\" >/dev/null 2>&1; ");
            b.append("UG=$(stat -c '%u:%g' \"$DB_CHK\" 2>/dev/null); ");
            b.append("SC=$(ls -Z \"$DB_CHK\" 2>/dev/null | awk '{print $1}'); ");
            b.append("BO=$($NS app_process -Djava.class.path=$APK /system/bin com.walter.claudewilson.Bridge cookies-clean \"$DB_REAL\" '")
                    .append(domainsCsv).append("' 2>&1); ");
            b.append("[ -n \"$UG\" ] && $NS sh -c 'chown \"$1\" \"$2\"* 2>/dev/null; chmod 600 \"$2\"* 2>/dev/null; [ -n \"$3\" ] && chcon \"$3\" \"$2\"* 2>/dev/null' _ \"$UG\" \"$DB_REAL\" \"$SC\"; ");
            b.append("echo \"BROWSER_CLEAN user=$U pkg=$B $(printf '%s' \"$BO\" | grep 'COOKIES_DELETED=' | head -n 1)\"; ");
            b.append("done; done; fi; ");
        }
        b.append("echo DONE");
        return b.toString();
    }

    /** Lists every user-installed location that will be wiped, so the dialog is a scan. */
    static String probe(String pkg) {
        boolean root = RootShell.rootReady();
        StringBuilder b = new StringBuilder();
        b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(pkg).append("$\" || continue; ");
        if (root) {
            b.append("for P in /data/user/$U/").append(pkg)
                    .append(" /data/user_de/$U/").append(pkg)
                    .append(" /storage/emulated/$U/Android/data/").append(pkg)
                    .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                    .append(" /storage/emulated/$U/Android/media/").append(pkg)
                    .append("; do [ -e \"$P\" ] && echo HIT \"$P\"; done; ");
        } else {
            b.append("echo HIT \"/data/user/$U/").append(pkg).append("\"; ");
            b.append("for P in /storage/emulated/$U/Android/data/").append(pkg)
                    .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                    .append(" /storage/emulated/$U/Android/media/").append(pkg)
                    .append("; do [ -e \"$P\" ] && echo HIT \"$P\"; done; ");
        }
        b.append("done; echo DONE");
        return root ? RootShell.wrapRoot(b.toString()) : b.toString();
    }

    static List<String> hits(String out) {
        List<String> list = new ArrayList<String>();
        if (out == null) return list;
        for (String line : out.split("\\n")) {
            if (line.startsWith("HIT ")) list.add(line.substring(4).trim());
        }
        return list;
    }

    /** Backwards-compatible multi-user clear command used by {@link TargetIdentityReset}. */
    static String command(String target, String browser) {
        boolean root = RootShell.rootReady();
        StringBuilder b = new StringBuilder();
        b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(target).append("$\" || continue; ");
        b.append("am force-stop --user \"$U\" ").append(target).append(" >/dev/null 2>&1; ");
        b.append("CO=$(pm clear --user \"$U\" ").append(target).append(" 2>&1); RC=$?; ");
        b.append("case \"$CO\" in *Success*) ;; *) RC=1 ;; esac; ");
        b.append("echo \"CLEAR pkg=").append(target).append(" user=$U rc=$RC out=$(printf '%s' \"$CO\" | head -n 1 | tr ' ' '_')\"; ");
        b.append("am force-stop --user \"$U\" ").append(target).append(" >/dev/null 2>&1; ");
        b.append("for P in /storage/emulated/$U/Android/data/").append(target)
                .append(" /storage/emulated/$U/Android/obb/").append(target)
                .append(" /storage/emulated/$U/Android/media/").append(target)
                .append(" /sdcard/Android/data/").append(target).append("; do rm -rf \"$P\"; done; ");
        if (root) {
            b.append("CE=$(find /data/user/$U/").append(target)
                    .append(" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
            b.append("DE=$(find /data/user_de/$U/").append(target)
                    .append(" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
        } else {
            b.append("CE=0; DE=0; [ \"$RC\" -eq 0 ] || CE=1; ");
        }
        b.append("EX=$(find /storage/emulated/$U/Android/data/").append(target)
                .append(" /storage/emulated/$U/Android/obb/").append(target)
                .append(" /storage/emulated/$U/Android/media/").append(target)
                .append(" -mindepth 1 2>/dev/null | wc -l); ");
        b.append("echo \"INTERNAL user=$U ce=$CE de=$DE ext=$EX\"; ");
        b.append("done; echo DONE");
        String script = b.toString();
        return root ? RootShell.wrapRoot(script) : script;
    }

    /** True only when every installed user cleared cleanly and any browser cookie cleanup reached 0 remaining. */
    static boolean ok(String out) {
        if (out == null || !out.contains("DONE")) return false;
        boolean sawClear = false;
        for (String line : out.split("\\n")) {
            line = line.trim();
            if (line.startsWith("CLEAR ")) {
                sawClear = true;
                if (!line.contains("rc=0")) return false;
            } else if (line.startsWith("INTERNAL ")) {
                if (!line.contains("ce=0 ") || !line.contains(" de=0 ")
                        || !line.endsWith("ext=0")) return false;
            } else if (line.startsWith("BROWSER_CLEAN ")) {
                if (!line.contains("COOKIES_REMAIN=0")) return false;
            }
        }
        return sawClear;
    }

    private static String joinDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String d : domains) {
            if (d == null || d.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(d);
        }
        return sb.toString();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}
