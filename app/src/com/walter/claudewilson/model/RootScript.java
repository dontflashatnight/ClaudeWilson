package com.walter.claudewilson.model;

/**
 * Root payloads, as text.
 *
 * <p>These scripts run under {@code su} and rewrite files in {@code /data/system}, so they are the
 * most dangerous strings in the app. Building them here — with the user id and package name as
 * parameters, and no Android types — means the shape of what gets executed can be asserted
 * offline instead of being eyeballed.
 *
 * <p>Quoting is *not* done here: wrap the result with {@link Command#su()}, which single-quotes
 * the whole payload so {@code $U}, {@code $O} and {@code $NEW} expand in the inner shell only.
 */
public final class RootScript {

    private RootScript() {
    }

    /** Restores the recorded SSAID for one package in one user, atomically. */
    public static String restoreSsaid(String user, String pkg) {
        return "U=" + user
                + "; F=/data/system/users/$U/settings_ssaid.xml; "
                + "B=/data/local/tmp/ssaid_orig_$U; "
                + "K=/data/local/tmp/ssaid_file_$U.abx; "
                + "[ -s $B ] || { echo aid-skip; exit 0; }; "
                + "[ -f $K ] || cp $F $K; "
                + "O=$(cat $B); T=/data/local/tmp/ssaid_r_$U.xml; "
                + "abx2xml $F $T 2>/dev/null || { echo FAIL:decode; exit 0; }; "
                + "sed -i \"/" + pkg + "/s/value=\\\"[^\\\"]*\\\"/value=\\\"$O\\\"/g\" $T; "
                + "sed -i \"/" + pkg + "/s/defaultValue=\\\"[^\\\"]*\\\"/defaultValue=\\\"$O\\\"/g\" $T; "
                + "W=/data/local/tmp/ssaid_w_$U.abx; "
                + "xml2abx $T $W || { echo FAIL:encode; exit 0; }; "
                + "mv $W $F && chown system:system $F"
                + " && chmod 600 $F && restorecon $F"
                + " || { echo FAIL:install; exit 0; }; "
                + "rm -f $B && echo aid-restored:$O";
    }

    /**
     * Rewrites the SSAID for one package in one user to a fresh value, keeping the original and
     * the untouched file, then reads the result back before reporting success.
     */
    public static String randomizeSsaid(String user, String pkg) {
        return "U=" + user
                + "; F=/data/system/users/$U/settings_ssaid.xml; "
                + "T=/data/local/tmp/ssaid_$U.xml; "
                + "B=/data/local/tmp/ssaid_orig_$U; "
                + "K=/data/local/tmp/ssaid_file_$U.abx; "
                + "abx2xml $F $T 2>/dev/null || { echo FAIL:decode; exit 0; }; "
                + "grep -q " + pkg + " $T || { echo no-target-entry; exit 0; }; "
                + "[ -f $K ] || cp $F $K; "
                + "[ -s $B ] || grep " + pkg + " $T | grep -o value=.[0-9a-f]*"
                + " | head -1 | cut -d\\\" -f2 > $B; "
                + "NEW=$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-16); "
                + "sed -i \"/" + pkg + "/s/value=\\\"[^\\\"]*\\\"/value=\\\"$NEW\\\"/g\" $T; "
                + "sed -i \"/" + pkg + "/s/defaultValue=\\\"[^\\\"]*\\\"/defaultValue=\\\"$NEW\\\"/g\" $T; "
                + "W=/data/local/tmp/ssaid_w_$U.abx; "
                + "xml2abx $T $W || { echo FAIL:encode; exit 0; }; "
                + "mv $W $F && chown system:system $F"
                + " && chmod 600 $F && restorecon $F"
                + " || { echo FAIL:install; exit 0; }; "
                + "V=/data/local/tmp/ssaid_v_$U.xml; "
                + "abx2xml $F $V 2>/dev/null && grep -q $NEW $V"
                + " || { echo FAIL:verify; exit 0; }; "
                + "rm -f $V && echo AID:$NEW u$U orig-saved";
    }
}
