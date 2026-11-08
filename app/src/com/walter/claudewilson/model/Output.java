package com.walter.claudewilson.model;

/**
 * Bounds a command's output without throwing away the verdict at its end.
 *
 * <p>A payload writes its markers at both ends: {@code timeout:}/{@code exc:} at the front, and
 * {@code SU_RC=} plus any late {@code FAIL:<step>} at the back. Cutting the tail therefore removes
 * exactly the bytes that say whether the command worked -- {@link ShellQuote#failed(String)} and
 * {@link ShellQuote#suRc(String)} read those last bytes, so a truncated run read as a clean one.
 * That is the failure mode this app exists to avoid, so the bound keeps both ends instead.
 *
 * <p>Pure: the rule is asserted in the offline self-test rather than on a device.
 */
public final class Output {

    /** Longest output handed to a TextView; longer runs keep their head and tail only. */
    public static final int MAX = 2000;

    private Output() {
    }

    /**
     * {@code out} unchanged when it fits, otherwise its head and tail joined by one marker line.
     *
     * <p>The result is never longer than {@code max}, so a caller can size a view from it. When
     * {@code max} is too small to hold both ends the tail wins, because that is where the status
     * marker lives.
     */
    public static String bounded(String out, int max) {
        if (out == null) {
            return "";
        }
        if (max <= 0 || out.length() <= max) {
            return out;
        }
        String marker = "\n...[" + (out.length() - max) + " chars]...\n";
        int keep = max - marker.length();
        if (keep < 2) {
            return out.substring(out.length() - max);
        }
        int head = keep / 2;
        int tail = keep - head;
        return out.substring(0, head) + marker + out.substring(out.length() - tail);
    }
}
