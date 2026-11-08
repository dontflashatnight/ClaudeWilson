package com.walter.claudewilson;

/**
 * Wipes the on-device data the target app (and the browser) report from.
 *
 * <p>Scope is deliberately narrow: only the target package's data for every user that has it
 * installed, plus its external directories. GMS, Play and other apps are untouched.
 *
 * <p>The old implementation ran one {@code pm clear} without a user id and reported success
 * whenever the external directory was gone. On a device with a work profile that cleared the
 * wrong user (or nothing), yet the UI said "cleared"; this version enumerates users, clears each
 * one, and verifies the internal and external directories before returning success.
 */
final class DataClean {
    private DataClean() {}

    /** Lists every user-installed location that will be wiped, so the dialog is a scan. */
    static String probe(String pkg) {
        StringBuilder b = new StringBuilder();
        b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(pkg).append("$\" || continue; ");
        b.append("for P in /data/user/$U/").append(pkg)
                .append(" /data/user_de/$U/").append(pkg)
                .append(" /storage/emulated/$U/Android/data/").append(pkg)
                .append(" /storage/emulated/$U/Android/obb/").append(pkg)
                .append(" /storage/emulated/$U/Android/media/").append(pkg)
                .append("; do [ -e \"$P\" ] && echo HIT \"$P\"; done; ");
        b.append("done; echo DONE");
        return b.toString();
    }

    static java.util.List<String> hits(String out) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (out == null) return list;
        for (String line : out.split("\\n")) {
            if (line.startsWith("HIT ")) list.add(line.substring(4).trim());
        }
        return list;
    }

    /**
     * Stops, clears and verifies the package for every user that has it. External storage is
     * per-user on modern Android, so {@code /sdcard} alone is not enough.
     */
    static String command(String target, String browser) {
        StringBuilder b = new StringBuilder();
        b.append("for U in $(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do ");
        b.append("pm list packages --user \"$U\" 2>/dev/null | grep -q \"^package:").append(target).append("$\" || continue; ");
        b.append("am force-stop --user \"$U\" ").append(target).append(" >/dev/null 2>&1; ");
        b.append("CO=$(pm clear --user \"$U\" ").append(target).append(" 2>&1); RC=$?; ");
        b.append("echo \"CLEAR pkg=").append(target).append(" user=$U rc=$RC out=$(printf '%s' \"$CO\" | head -n 1 | tr ' ' '_')\"; ");
        b.append("am force-stop --user \"$U\" ").append(target).append(" >/dev/null 2>&1; ");
        b.append("for P in /storage/emulated/$U/Android/data/").append(target)
                .append(" /storage/emulated/$U/Android/obb/").append(target)
                .append(" /storage/emulated/$U/Android/media/").append(target)
                .append(" /sdcard/Android/data/").append(target).append("; do rm -rf \"$P\"; done; ");
        b.append("CE=$(find /data/user/$U/").append(target)
                .append(" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
        b.append("DE=$(find /data/user_de/$U/").append(target)
                .append(" -mindepth 1 ! -type d 2>/dev/null | wc -l); ");
        b.append("EX=$(find /storage/emulated/$U/Android/data/").append(target)
                .append(" /storage/emulated/$U/Android/obb/").append(target)
                .append(" /storage/emulated/$U/Android/media/").append(target)
                .append(" -mindepth 1 2>/dev/null | wc -l); ");
        b.append("echo \"INTERNAL user=$U ce=$CE de=$DE ext=$EX\"; ");
        b.append("done; echo DONE");
        String script = b.toString();
        // pm clear needs the su domain on this ROM; running the loop directly from root
        // (uid 0 but shell SELinux domain) can return success without clearing the sandbox.
        return "if su -c id -u >/dev/null 2>&1; then "
                + "su -c " + com.walter.claudewilson.model.ShellQuote.quote(script)
                + "; else " + script + "; fi";
    }

    /** True only when every installed user reported a successful pm clear and no leftovers. */
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
            }
        }
        return sawClear;
    }

    /**
     * The clear command uses the same per-user scan; this list is only a preview and names the
     * exact paths, not a guessed single sandbox.
     */
}
