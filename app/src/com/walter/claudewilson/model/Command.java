package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one {@code sh -c} script out of named steps.
 *
 * <p>Each step carries its own failure marker, so a caller can tell *which* step failed. The form
 * this replaces appended {@code echo RC=$?} after a chain of {@code ;} commands, where the status
 * is always that of the last {@code echo} -- a failed apply and a successful one printed the same
 * thing.
 *
 * <p>Pure: assembling a script and reading its output need no device, so both are covered by the
 * offline self-test. Quoting lives in {@link ShellQuote} and validation stays with the caller,
 * which is the only place that knows whether a value is a token or free text.
 */
public final class Command {
    private final boolean viaSu;
    private final List<String> prefixes = new ArrayList<String>();
    private final List<String[]> steps = new ArrayList<String[]>();
    private String done = "DONE";

    private Command(boolean viaSu) {
        this.viaSu = viaSu;
    }

    /** A plain {@code sh -c} script. */
    public static Command sh() {
        return new Command(false);
    }

    /**
     * The same script, but handed to {@code su -c} so it runs as root.
     *
     * <p>The payload is single-quoted as a whole, which is what keeps {@code $U} and friends
     * expanding in the *inner* shell while nothing expands in the outer one. Writing that quoting
     * by hand in five places is how the escapes drifted apart.
     */
    public static Command su() {
        return new Command(true);
    }

    /** Appended as-is before the steps: assignments and other shell plumbing. */
    public Command raw(String fragment) {
        if (fragment != null && !fragment.isEmpty()) {
            prefixes.add(fragment);
        }
        return this;
    }

    /** Appended as {@code command || echo FAIL:name; }. */
    public Command step(String name, String command) {
        if (name == null || command == null) {
            throw new IllegalArgumentException("step needs a name and a command");
        }
        steps.add(new String[]{name, command});
        return this;
    }

    /** Marker echoed once, after every step. */
    public Command done(String marker) {
        this.done = marker == null ? "DONE" : marker;
        return this;
    }

    public String build() {
        StringBuilder out = new StringBuilder();
        for (String prefix : prefixes) {
            out.append(prefix).append("; ");
        }
        for (String[] step : steps) {
            out.append(step[1]).append(" || echo FAIL:").append(step[0]).append("; ");
        }
        out.append("echo ").append(done);
        if (!viaSu) {
            return out.toString();
        }
        // SU_RC is su's own status, so a refused or missing su is distinguishable from a payload
        // that ran and exited non-zero: both used to look like an empty result.
        return "su -c " + ShellQuote.quote(out.toString()) + "; echo SU_RC=$?";
    }

    public String[] stepNames() {
        String[] names = new String[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            names[i] = steps.get(i)[0];
        }
        return names;
    }
}
