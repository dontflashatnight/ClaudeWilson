package com.walter.claudewilson;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetEvidence;
import com.walter.claudewilson.model.TargetSpec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the read-only evidence record for one target.
 *
 * <p>The app's package-visibility view can omit an installed target (observed on One UI 7 with
 * ChatGPT while {@code QUERY_ALL_PACKAGES} is granted), so the root shell is the authoritative
 * fallback for package shape. Local stores are enumerated by name only: no file content is read,
 * no identifier is returned, and the record never contains a token.
 */
final class TargetEvidenceCollector {
    private static final Pattern VERSION = Pattern.compile("versionName=([^\\s]+)");
    private static final Pattern UID = Pattern.compile("(?:appId|userId)=(\\d+)");
    private static final Pattern INSTALLER = Pattern.compile("installerPackageName=([^\\s]+)");
    private static final String[] STORES = {
            "shared_prefs", "databases", "files", "app_webview", "no_backup"};

    TargetEvidence collect(Context context, TargetSpec spec, boolean root) {
        if (spec == null) {
            return TargetEvidence.EMPTY;
        }
        TargetEvidence.Builder out = TargetEvidence.builder().collected(true);
        boolean packageRead = fromPackageManager(context, spec, out);
        if (!packageRead) {
            if (root) {
                fromRootPackage(spec, out);
            } else {
                out.note("package not visible").installed(false);
            }
        } else if (root) {
            // PackageInfo.splitNames is not populated by a documented public flag on this
            // platform level; pm path is the stable source and works for a PM-hidden package too.
            addSplits(spec, out);
        }
        boolean wantsData = !spec.identityMarkers().isEmpty()
                || !spec.integrityMarkers().isEmpty();
        out.dataRequested(wantsData);
        if (wantsData) {
            if (root) {
                scanNames(spec, out);
            } else {
                out.note("root unavailable; local stores not read");
            }
        }
        if (root) {
            out.suView(readSuView(spec));
            out.configPresent(new RootActions(context).configPresent(spec));
        }
        return out.build();
    }

    /** Fast path; a visibility miss is not yet "not installed". */
    private static boolean fromPackageManager(Context context, TargetSpec spec,
                                              TargetEvidence.Builder out) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(spec.packageName(), 0);
            out.installed(true).version(info.versionName == null ? "" : info.versionName)
                    .source("pm");
            if (info.applicationInfo != null) {
                out.uid(String.valueOf(info.applicationInfo.uid));
            }
            if (info.splitNames != null) {
                for (String split : info.splitNames) {
                    out.split(split);
                }
            }
            try {
                String installer = pm.getInstallSourceInfo(spec.packageName())
                        .getInstallingPackageName();
                out.installer(installer == null ? "" : installer);
            } catch (Throwable ignored) {
                // Installer is optional evidence, not a reason to lose the package record.
            }
            return true;
        } catch (Throwable t) {
            com.walter.claudewilson.platform.Diag.warn("target-evidence",
                    "pm query " + spec.packageName(), t);
            return false;
        }
    }

    /** Root fallback: package shape from dumpsys/pm path, no inference from PM silence. */
    private static void fromRootPackage(TargetSpec spec, TargetEvidence.Builder out) {
        String dump = RootShell.run(
                "dumpsys package " + spec.packageName()
                        + " 2>/dev/null | grep -E 'versionName=|appId=|installerPackageName='"
                        + " | head -8", 512);
        String version = first(VERSION, dump);
        String uid = first(UID, dump);
        String installer = first(INSTALLER, dump);
        String paths = RootShell.run("pm path " + spec.packageName() + " 2>/dev/null", 512);
        boolean installed = !version.isEmpty() || paths.contains("package:");
        out.installed(installed).version(version).uid(uid).installer(installer)
                .source(installed ? "root" : "")
                .note(installed ? "package hidden from pm visibility" : "not installed");
        addSplitsFromPaths(paths, out);
    }

    /** Splits from {@code pm path}; base.apk is not a split. */
    private static void addSplits(TargetSpec spec, TargetEvidence.Builder out) {
        addSplitsFromPaths(RootShell.run("pm path " + spec.packageName() + " 2>/dev/null", 512),
                out);
    }

    private static void addSplitsFromPaths(String paths, TargetEvidence.Builder out) {
        for (String line : paths.split("\\n")) {
            String path = line.startsWith("package:") ? line.substring(8).trim() : "";
            if (path.endsWith(".apk")) {
                int slash = path.lastIndexOf('/');
                String name = path.substring(slash + 1);
                if (!"base.apk".equals(name)) {
                    out.split(name);
                }
            }
        }
    }

    /**
     * One root pass over fixed store directories. File names are filtered against the target's
     * configured markers; only marker names and counts leave this method.
     */
    private static void scanNames(TargetSpec spec, TargetEvidence.Builder out) {
        String user = String.valueOf(android.os.Process.myUid() / 100000);
        String pkg = spec.packageName();
        StringBuilder script = new StringBuilder();
        script.append("U=").append(user).append("; P=").append(pkg).append("; ");
        // The app's root shell can live in an inherited mount namespace where another app's
        // /data/data path is absent. /proc/1/root is the init namespace, so it sees the real
        // package data; the direct path stays as the fallback for a normal namespace.
        script.append("CE=/proc/1/root/data/data/$P; [ -d \"$CE\" ] || CE=/data/data/$P; ");
        script.append("DE=/proc/1/root/data/user_de/$U/$P; ")
                .append("[ -d \"$DE\" ] || DE=/data/user_de/$U/$P; ");
        script.append("for R in \"$CE\" \"$DE\"; do ");
        script.append("[ -d \"$R\" ] || continue; echo \"ROOT $R\"; ");
        for (String store : STORES) {
            script.append("[ -e \"$R/").append(store).append("\" ] || continue; ")
                    .append("echo \"STORE ").append(store).append("\"; ")
                    // Only the named stores: walking the whole sandbox reaches cache/ and can
                    // exceed the shell timeout before any answer is returned.
                    .append("find \"$R/").append(store)
                    .append("\" -maxdepth 4 -type f 2>/dev/null")
                    .append(" | sed \"s|^$R/||\" | head -200")
                    .append(" | while IFS= read -r F; do echo \"FILE $F\"; done; ");
        }
        script.append("done");

        String dump = RootShell.run(script.toString(), 8192);
        if (dump == null || !dump.contains("ROOT ")) {
            out.dataReadable(false).note("target data not readable");
            return;
        }
        out.dataReadable(true);
        int files = 0;
        for (String line : dump.split("\\n")) {
            if (line.startsWith("STORE ")) {
                String store = line.substring(6).trim();
                out.store(store);
                if ("app_webview".equals(store)) {
                    out.webViewStore(true);
                }
            } else if (!line.startsWith("FILE ")) {
                continue;
            } else if (files >= 400) {
                break;
            } else {
                files++;
                String path = line.substring(5).trim().toLowerCase(Locale.ROOT);
                if (path.startsWith("app_webview")) {
                    out.webViewStore(true);
                }
                if (path.contains("cookie")) {
                    out.cookieStore(true);
                }
                if (path.contains("local storage") || path.contains("localstorage")) {
                    out.localStorageStore(true);
                }
                if (path.contains("indexeddb")) {
                    out.indexedDbStore(true);
                }
                for (String marker : spec.identityMarkers()) {
                    if (path.contains(marker)) {
                        out.identityKind(marker);
                    }
                }
                for (String marker : spec.integrityMarkers()) {
                    if (path.contains(marker)) {
                        out.integrityKind(marker);
                    }
                }
            }
        }
        out.filesSeen(files);
    }

    private static String first(Pattern pattern, String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * Reads the target's own self-check. This is the only su fact that describes the target's
     * view; the catalog stat in the environment card describes the control app instead.
     */
    private static int readSuView(TargetSpec spec) {
        if (spec == null || !ShellQuote.packageName(spec.packageName())) {
            return TargetEvidence.SU_UNKNOWN;
        }
        String pkg = spec.packageName();
        String out = RootShell.run(
                "F=/proc/1/root/data/data/" + pkg + "/files/hookcheck.txt; "
                        + "[ -s \"$F\" ] || F=/data/data/" + pkg + "/files/hookcheck.txt; "
                        + "cat \"$F\" 2>/dev/null",
                8192);
        if (out.contains("su_system=hidden")) {
            return TargetEvidence.SU_HIDDEN;
        }
        if (out.contains("su_system=visible")) {
            return TargetEvidence.SU_VISIBLE;
        }
        return TargetEvidence.SU_UNKNOWN;
    }
}
