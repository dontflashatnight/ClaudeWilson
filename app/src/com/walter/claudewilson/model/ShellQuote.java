package com.walter.claudewilson.model;

/**
 * Shell quoting for the values that reach a {@code sh -c} command line.
 *
 * <p>Two different needs: a token that is safe to interpolate unquoted (timezone ids, locale
 * tags, ISO codes), and a value that may contain anything (carrier names include spaces and
 * non-ASCII). The first gets validated, the second gets quoted -- never the other way round.
 *
 * <p>Pure, so both rules are assertable without a device.
 */
public final class ShellQuote {

    /** Characters that may appear unquoted in the command lines this app builds. */
    private static final String TOKEN = "[A-Za-z0-9_+/,\\-.]{1,64}";

    private ShellQuote() {
    }

    /** True when the value can be interpolated without quoting. */
    public static boolean safeToken(String value) {
        return value != null && value.matches(TOKEN);
    }

    /** Wraps a value in single quotes, escaping any single quote it contains. */
    public static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Digits only, for subscription ids and user ids taken from the UI. */
    public static boolean numeric(String value) {
        return value != null && value.matches("\\d{1,6}");
    }

    /** A package name: dot separated identifiers, no shell metacharacters possible. */
    public static boolean packageName(String value) {
        return value != null && value.length() <= 128
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");
    }

    /** A command echo marker; used instead of matching output prefixes. */
    public static boolean failed(String output) {
        if (output == null
                || output.contains("FAIL:")
                || output.startsWith("timeout")
                || output.startsWith("exc:")
                || output.startsWith("refused")) {
            return true;
        }
        int rc = suRc(output);
        if (rc != Integer.MIN_VALUE && rc != 0) {
            return true;
        }
        int shRc = shellRc(output);
        return shRc != Integer.MIN_VALUE && shRc != 0;
    }

    /**
     * Line-anchored {@code rc=<n>} status emitted by the shell runners when no {@code SU_RC=}
     * wrapper was used, or {@link Integer#MIN_VALUE} when absent. Anchored to the start of a line
     * so mid-line {@code rc=0} log fields are never mistaken for the transport exit code.
     */
    public static int shellRc(String output) {
        if (output == null || output.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int at = -1;
        if (output.startsWith("rc=")) {
            at = 0;
        }
        int nl = output.lastIndexOf("\nrc=");
        if (nl >= 0) {
            at = nl + 1;
        }
        if (at < 0) {
            return Integer.MIN_VALUE;
        }
        int start = at + "rc=".length();
        int end = start;
        if (end < output.length() && output.charAt(end) == '-') {
            end++;
        }
        int digits = end;
        while (end < output.length() && Character.isDigit(output.charAt(end))) {
            end++;
        }
        if (end == digits) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(output.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * The {@code SU_RC} value a su-wrapped command echoes, or {@link Integer#MIN_VALUE} when the
     * marker is absent (the command did not go through su).
     */
    public static int suRc(String output) {
        if (output == null) {
            return Integer.MIN_VALUE;
        }
        int at = output.lastIndexOf("SU_RC=");
        if (at < 0) {
            return Integer.MIN_VALUE;
        }
        int start = at + "SU_RC=".length();
        int end = start;
        while (end < output.length() && Character.isDigit(output.charAt(end))) {
            end++;
        }
        if (end == start) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(output.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * The same status as one audit token: {@code su=0}, {@code su=1}, or {@code su=absent}.
     *
     * <p>Written out so a log line answers "did su run this at all" without re-parsing; the raw
     * sentinel {@link Integer#MIN_VALUE} would print as a number that looks like a status.
     */
    public static String suRcTag(String output) {
        int rc = suRc(output);
        return rc == Integer.MIN_VALUE ? "su=absent" : "su=" + rc;
    }

    /**
     * True when the status means the {@code su} binary itself was gone, not that it refused.
     *
     * <p>126/127 are the shell's "found but not executable" / "command not found". KernelSU hides
     * {@code su} from unauthorised uids instead of answering with a permission error, so the
     * refusal this app has to explain actually arrives as a missing command. Saying "refused"
     * for 127 would name the wrong cause.
     */
    public static boolean suMissing(int rc) {
        return rc == 126 || rc == 127;
    }
}
