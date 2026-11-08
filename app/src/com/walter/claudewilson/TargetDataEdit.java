package com.walter.claudewilson;

import android.content.Context;
import android.util.Base64;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One narrowly scoped target-data edit: the dialling country hint in ChatGPT's DataStore.
 *
 * <p>The App's root shell sees the target through {@code /proc/1/root}; the file is copied to a
 * timestamped backup before the replacement, the whole file is written back through the same
 * inode (so owner/mode/context survive), and the result is read back. A running target is
 * refused: editing a file its DataStore may rewrite is not a deterministic operation.
 */
final class TargetDataEdit {
    private static final Pattern HINT = Pattern.compile(
            "\"country_code_hint\"\\s*:\\s*\"([^\"]*)\"");

    String setCountryHint(Context context, TargetSpec target, String iso) {
        if (target == null || !"chatgpt".equals(target.id())) {
            return "failed: unsupported target";
        }
        if (iso == null || !iso.matches("[A-Za-z]{2}")) {
            return "failed: country must be two letters";
        }
        if (!ShellQuote.packageName(target.packageName())) {
            return "failed: invalid package";
        }
        if (!RootShell.rootReady()) {
            return "failed: root not ready";
        }
        if (new RootActions(context).targetRunning(target)) {
            return "failed: target is running; stop it first";
        }
        String pkg = target.packageName();
        String root = "/proc/1/root/data/data/" + pkg
                + "/files/datastore/global_phone_country_codes.json";
        String direct = "/data/data/" + pkg + "/files/datastore/global_phone_country_codes.json";
        String script = "F=" + root + "; [ -f \"$F\" ] || F=" + direct + "; "
                + "[ -f \"$F\" ] || { echo MISSING; exit 0; }; cat \"$F\"";
        String text = RootShell.run(script, 65536, 20000L);
        if (text == null || text.startsWith("exc:") || text.startsWith("timeout:")
                || text.contains("MISSING")) {
            return "failed: target data unreadable";
        }
        Matcher matcher = HINT.matcher(text);
        if (!matcher.find()) {
            return "failed: country_code_hint key absent";
        }
        String old = matcher.group(1);
        String upper = iso.toUpperCase(java.util.Locale.ROOT);
        String updated = matcher.replaceFirst("\"country_code_hint\":\"" + upper + "\"");
        String encoded = Base64.encodeToString(updated.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String stamp = String.valueOf(System.currentTimeMillis());
        String backup = "/data/local/tmp/claudewilson/backup/" + pkg
                + "/global_phone_country_codes." + stamp + ".json";
        String write = "F=" + root + "; [ -f \"$F\" ] || F=" + direct + "; "
                + "B=" + backup + "; mkdir -p $(dirname \"$B\") && cp -p \"$F\" \"$B\" "
                + "&& printf %s '" + encoded + "' | base64 -d > \"$F\" "
                + "&& grep -o '\"country_code_hint\":\"[^\"]*\"' \"$F\"";
        String result = RootShell.run(write, 4096, 20000L);
        if (result == null || result.startsWith("exc:") || result.startsWith("timeout:")
                || !result.contains("\"country_code_hint\":\"" + upper + "\"")) {
            return "failed: write not verified; backup=" + backup;
        }
        return "ok " + old + " -> " + upper + " · backup " + backup;
    }

    /**
     * Restore the newest timestamped backup. Returns the restored value in {@code ok <iso>}; the
     * caller uses it to update the item without a full rescan.
     */
    String restoreLast(Context context, TargetSpec target) {
        if (target == null || !"chatgpt".equals(target.id())) {
            return "failed: unsupported target";
        }
        String pkg = target.packageName();
        String dir = "/data/local/tmp/claudewilson/backup/" + pkg;
        String latest = RootShell.run(
                "ls -t " + dir + "/*.json 2>/dev/null | head -1", 512).trim();
        return restorePath(context, target, latest);
    }

    /** Restore one known backup path (used by the apply rollback). */
    String restorePath(Context context, TargetSpec target, String backup) {
        if (target == null || !"chatgpt".equals(target.id())) {
            return "failed: unsupported target";
        }
        if (!RootShell.rootReady()) {
            return "failed: root not ready";
        }
        if (new RootActions(context).targetRunning(target)) {
            return "failed: target is running; stop it first";
        }
        String pkg = target.packageName();
        String dir = "/data/local/tmp/claudewilson/backup/" + pkg + "/";
        if (backup == null || !backup.startsWith(dir) || !backup.endsWith(".json")) {
            return "failed: no backup";
        }
        String root = "/proc/1/root/data/data/" + pkg
                + "/files/datastore/global_phone_country_codes.json";
        String direct = "/data/data/" + pkg
                + "/files/datastore/global_phone_country_codes.json";
        String script = "F=" + root + "; [ -f \"$F\" ] || F=" + direct + "; "
                + "cp -p " + backup + " \"$F\" "
                + "&& grep -o '\"country_code_hint\":\"[^\"]*\"' \"$F\"";
        String out = RootShell.run(script, 4096, 20000L);
        Matcher matcher = HINT.matcher(out == null ? "" : out);
        if (!matcher.find()) {
            return "failed: restore not verified";
        }
        return "ok " + matcher.group(1);
    }
}
