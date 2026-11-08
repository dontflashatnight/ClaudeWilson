package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a {@link Command} actually reported.
 *
 * <p>A typed reading of the transport's string: which steps printed a failure marker, and whether
 * the result can be trusted at all. Callers used to compare output prefixes
 * ({@code "aid-restored".equals(...)}), which silently reads as "failed" for every unexpected
 * form.
 */
public final class Outcome {
    private final boolean ok;
    private final String output;
    private final List<String> failedSteps;
    private final List<String> skippedSteps;

    private Outcome(boolean ok, String output, List<String> failedSteps,
                    List<String> skippedSteps) {
        this.ok = ok;
        this.output = output == null ? "" : output;
        this.failedSteps = Collections.unmodifiableList(failedSteps);
        this.skippedSteps = Collections.unmodifiableList(skippedSteps);
    }

    public static Outcome of(String output, String... stepNames) {
        List<String> failed = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        if (stepNames != null) {
            for (String name : stepNames) {
                if (output != null && output.contains("FAIL:" + name)) {
                    failed.add(name);
                }
                if (output != null && output.contains("SKIP:" + name)) {
                    skipped.add(name);
                }
            }
        }
        return new Outcome(!ShellQuote.failed(output), output, failed, skipped);
    }

    public boolean ok() {
        return ok;
    }

    public String output() {
        return output;
    }

    /** Names of the steps that reported a failure, in the order they were declared. */
    public List<String> failedSteps() {
        return failedSteps;
    }

    /** Names of the steps the script deliberately skipped (SKIP:&lt;name&gt; marker). */
    public List<String> skippedSteps() {
        return skippedSteps;
    }

    /** One line for the log: ok, or the failing steps. */
    public String summary() {
        if (ok) {
            return skippedSteps.isEmpty()
                    ? "ok" : "ok, skipped: " + String.join(",", skippedSteps);
        }
        if (failedSteps.isEmpty()) {
            return "failed: " + output.trim();
        }
        return "failed: " + String.join(",", failedSteps);
    }
}
