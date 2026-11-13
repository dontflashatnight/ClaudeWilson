package com.walter.claudewilson;

import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.ShellQuote;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The build identity a persona puts on the whole system, not only into the target process.
 *
 * <p>Every field is derived from the same device row, and the fingerprint is rebuilt from those
 * fields instead of being copied from the table: a vendor build can otherwise carry a hybrid
 * fingerprint whose incremental suffix does not match its product/device pair.
 *
 * <p>Two layers are written together. {@code ro.*} is the build identity that every process reads
 * at start; {@code gsm.operator.*} is the visited-network trio that the telemetry backend reads
 * with {@code getNetworkOperatorName()} and friends. The first is replayed from a boot script so a
 * reboot does not silently return the device to its own identity; the second is runtime state and
 * is re-created by the phone process, so it is written again on every apply.
 *
 * <p>Nothing here runs without the root channel: {@code ro.*} cannot be changed by a shell uid, and
 * {@code resetprop} is the only writer that reaches the property area after boot.
 */
final class SystemBuild {

    /** Stable digest of the build-identity fields, so a region switch can skip an unchanged write. */
    static String signature(String[] dv) {
        if (dv == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : buildValues(dv).entrySet()) {
                digest.update((e.getKey() + "=" + e.getValue() + "\n").getBytes("UTF-8"));
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** First value written wins: this file is the pre-persona snapshot, read back on revert. */
    static final String BACKUP = "/data/local/tmp/.cw-props.bak";
    static final String BOOT = "/data/adb/service.d/99-claudewilson-build.sh";

    private static final String RP =
            "RP=$(command -v resetprop || echo /data/adb/ksu/bin/resetprop)";

    /** Keys that make up the build identity. Order is the order they are written in. */
    private static final String[] BUILD_KEYS = {
            "ro.product.manufacturer",
            "ro.product.brand",
            "ro.product.model",
            "ro.product.device",
            "ro.product.name",
            "ro.product.board",
            "ro.hardware",
            "ro.build.fingerprint",
            "ro.build.display.id",
            "ro.build.id",
            "ro.build.version.incremental",
            "ro.build.version.release",
            "ro.build.tags",
            "ro.build.type",
            "ro.system.build.fingerprint",
            "ro.product.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.system_ext.build.fingerprint",
            "ro.system_dlkm.build.fingerprint",
            "ro.vendor_dlkm.build.fingerprint",
            "ro.bootimage.build.fingerprint",
    };

    private static final String[] OPERATOR_KEYS = {
            "gsm.operator.numeric",
            "gsm.operator.iso-country",
            "gsm.operator.alpha",
    };

    private SystemBuild() {
    }

    /** Build values for a 14-field device row; the fingerprint is derived, never carried over. */
    static Map<String, String> buildValues(String[] dv) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String fingerprint = PersonaSynth.fingerprint(dv[2], dv[5], dv[4], dv[11], dv[9], dv[10]);
        out.put("ro.product.manufacturer", dv[1]);
        out.put("ro.product.brand", dv[2]);
        out.put("ro.product.model", dv[3]);
        out.put("ro.product.device", dv[4]);
        out.put("ro.product.name", dv[5]);
        out.put("ro.product.board", dv[7]);
        out.put("ro.hardware", dv[6]);
        out.put("ro.build.fingerprint", fingerprint);
        out.put("ro.build.display.id", dv[9] + "." + dv[10]);
        out.put("ro.build.id", dv[9]);
        out.put("ro.build.version.incremental", dv[10]);
        out.put("ro.build.version.release", dv[11]);
        out.put("ro.build.tags", "release-keys");
        out.put("ro.build.type", "user");
        out.put("ro.system.build.fingerprint", fingerprint);
        out.put("ro.product.build.fingerprint", fingerprint);
        out.put("ro.vendor.build.fingerprint", fingerprint);
        out.put("ro.odm.build.fingerprint", fingerprint);
        // Samsung keeps one fingerprint per partition; leaving them on the real build lets a
        // reader pick up the device identity from any of them after the main one moved.
        out.put("ro.system_ext.build.fingerprint", fingerprint);
        out.put("ro.system_dlkm.build.fingerprint", fingerprint);
        out.put("ro.vendor_dlkm.build.fingerprint", fingerprint);
        out.put("ro.bootimage.build.fingerprint", fingerprint);
        return out;
    }

    /** Visited-network trio, so the telemetry paths that read it stop seeing the real network. */
    static Map<String, String> operatorValues(String op, String iso, String carrier) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("gsm.operator.numeric", op);
        out.put("gsm.operator.iso-country", iso);
        out.put("gsm.operator.alpha", carrier);
        return out;
    }

    /** A property value cannot carry a newline; the file form would split it into a bogus key. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    /** SIM operator trio; the platform refreshes this independently from the SIM record. */
    static Map<String, String> simValues(String op, String iso, String carrier) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("gsm.sim.operator.numeric", op);
        out.put("gsm.sim.operator.iso-country", iso);
        out.put("gsm.sim.operator.alpha", carrier);
        return out;
    }

    /** Writes one value into every slot of a per-slot property list. */
    private static String fillFunction() {
        return "fill() { key=\"$1\"; val=\"$2\"; "
                + "cur=\"$(getprop \"$key\" || true)\"; "
                + "n=$(( $(printf '%s' \"$cur\" | tr -cd ',' | wc -c) + 1 )); "
                + "out=\"$val\"; i=1; "
                + "while [ \"$i\" -lt \"$n\" ]; do out=\"$out,$val\"; i=$((i+1)); done; "
                + "$RP \"$key\" \"$out\"; }\n";
    }

    /** One transaction for both telephony trios, filling every active SIM slot. */
    static String telephonyScript(String op, String iso, String carrier) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.putAll(operatorValues(op, iso, carrier));
        values.putAll(simValues(op, iso, carrier));
        StringBuilder sh = new StringBuilder("set -e\n");
        sh.append(RP).append('\n');
        sh.append(fillFunction());
        for (Map.Entry<String, String> e : values.entrySet()) {
            sh.append("fill ").append(ShellQuote.quote(e.getKey()))
                    .append(' ').append(ShellQuote.quote(e.getValue())).append('\n');
        }
        return sh.toString();
    }

    /**
     * SIM operator trio alone. Some telephony stacks look the data APN up from this trio, so it
     * must keep the physical card's values while the visited-network trio carries the persona.
     */
    static String simScript(String op, String iso, String carrier) {
        StringBuilder sh = new StringBuilder("set -e\n");
        sh.append(RP).append('\n');
        sh.append(fillFunction());
        for (Map.Entry<String, String> e : simValues(op, iso, carrier).entrySet()) {
            sh.append("fill ").append(ShellQuote.quote(e.getKey()))
                    .append(' ').append(ShellQuote.quote(e.getValue())).append('\n');
        }
        return sh.toString();
    }

    /**
     * Display name only. Vendor SIM managers cache {@code gsm.sim.operator.alpha} as the
     * subscription label; the numeric MCC/MNC and country stay untouched so data-APN lookup is
     * unaffected.
     */
    static String alphaScript(String alpha) {
        StringBuilder sh = new StringBuilder("set -e\n");
        sh.append(RP).append('\n');
        sh.append(fillFunction());
        sh.append("fill ").append(ShellQuote.quote("gsm.sim.operator.alpha"))
                .append(' ').append(ShellQuote.quote(alpha)).append('\n');
        return sh.toString();
    }

    /** The trio on its own: a region applies it even when no device row was picked. */
    static String operatorScript(String op, String iso, String carrier) {
        StringBuilder sh = new StringBuilder("set -e\n");
        sh.append(RP).append('\n');
        backup(sh);
        // Per-slot properties: the whole list is rewritten with the persona value in every slot.
        // Writing one value left the active slot holding the real network (measured: a two-slot
        // list after a radio update), which made the row fall back to the physical card.
        sh.append(fillFunction());
        for (Map.Entry<String, String> e : operatorValues(op, iso, carrier).entrySet()) {
            sh.append("fill ").append(ShellQuote.quote(e.getKey()))
                    .append(' ').append(ShellQuote.quote(e.getValue())).append('\n');
        }
        return sh.toString();
    }

    /**
     * Snapshot of every key before the first write, shared by both scripts.
     *
     * <p>First writer wins: whichever runs first captures the pre-persona values, so the revert
     * restores the device's own identity even when the operator trio and the build identity are
     * applied by different steps.
     */
    private static void backup(StringBuilder sh) {
        sh.append("BAK=").append(BACKUP).append('\n');
        sh.append("[ -f $BAK ] || { : > $BAK; for k in");
        for (String key : keys()) {
            sh.append(' ').append(key);
        }
        sh.append("; do echo \"$k=$(getprop $k)\" >> $BAK; done; }\n");
        // The control app reads this file to answer "what was the real device". A root umask of
        // 077 would make it owner-only and that answer unreadable to the app's own uid.
        sh.append("chmod 644 $BAK 2>/dev/null || true\n");
    }

    /**
     * Writes the build identity and the visited-network trio for this boot.
     *
     * <p>No boot-time replay is installed. A service.d prop writer runs in the same early boot
     * window as the injection framework's module mount; when it is slow or the prop store is not
     * ready it can block that mount. The identity is applied while the app is running instead.
     *
     * <p>{@code set -e} makes one failed {@code resetprop} fail the whole step, so the caller's
     * {@code FAIL:build-props} marker is truthful instead of reporting the last command's status.
     */
    static String applyScript(String[] dv, String op, String iso, String carrier) {
        Map<String, String> values = new LinkedHashMap<String, String>(buildValues(dv));
        if (op != null && op.matches("\\d{5,6}") && iso != null && !iso.isEmpty()) {
            values.putAll(operatorValues(op, iso, carrier == null ? "" : carrier));
        }
        StringBuilder sh = new StringBuilder("set -e\n");
        sh.append(RP).append('\n');
        backup(sh);
        // One resetprop process for the whole identity. The per-key form cost ~340ms for 22 keys;
        // a file load is ~40–60ms and cannot leave half the identity written because one exec
        // between keys was slow.
        sh.append("T=/data/local/tmp/.cw-props.$$\n: > $T\n");
        for (Map.Entry<String, String> e : values.entrySet()) {
            sh.append("printf '%s=%s\\n' ")
                    .append(ShellQuote.quote(e.getKey())).append(' ')
                    .append(ShellQuote.quote(oneLine(e.getValue()))).append(" >> $T\n");
        }
        sh.append("$RP -f $T || { rm -f $T; echo FAIL:write-props; exit 1; }\n");
        sh.append("rm -f $T\n");
        sh.append("rm -f ").append(BOOT).append('\n');
        return sh.toString();
    }

    /**
     * Restores every key captured before the first apply and drops the boot replay.
     *
     * <p>No {@code set -e}: a property that cannot be restored (one that vanished since the
     * snapshot was taken) must not fail the entire revert. An empty snapshot value means the key
     * did not exist, so it is deleted instead of being written blank -- {@code resetprop} rejects
     * an empty value.
     */
    static String restoreScript() {
        StringBuilder sh = new StringBuilder();
        sh.append(RP).append('\n');
        sh.append("BAK=").append(BACKUP).append('\n');
        // One file load for the values that still exist; only keys that were absent need a
        // per-key delete. The old loop started one resetprop per snapshot line (~340ms for 24).
        sh.append("if [ -f $BAK ]; then T=/data/local/tmp/.cw-restore.$$; "
                        + "D=/data/local/tmp/.cw-del.$$; : > $T; : > $D; "
                        + "while IFS= read -r line; do "
                        + "k=${line%%=*}; v=${line#*=}; "
                        + "if [ -n \"$v\" ]; then printf '%s=%s\\n' \"$k\" \"$v\" >> $T; "
                        + "else echo \"$k\" >> $D; fi; done < $BAK; "
                        + "[ -s $T ] && $RP -f $T; "
                        + "while IFS= read -r k; do $RP --delete \"$k\"; done < $D; "
                        + "rm -f $T $D $BAK; fi\n");
        sh.append("rm -f ").append(BOOT).append('\n');
        sh.append("true\n");
        return sh.toString();
    }

    private static java.util.List<String> keys() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String k : BUILD_KEYS) {
            out.add(k);
        }
        for (String k : OPERATOR_KEYS) {
            out.add(k);
        }
        return out;
    }

}
