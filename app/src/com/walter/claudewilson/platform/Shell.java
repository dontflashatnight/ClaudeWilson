package com.walter.claudewilson.platform;

import com.walter.claudewilson.model.Text;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one short-lived binary argument vector and returns what it wrote.
 *
 * <p>Properties the old helper lacked: an explicit charset (the platform default silently mangles
 * non-ASCII output), a result that distinguishes failure from an empty answer, and a hard cap on
 * how long we wait for the child to exit. Output is drained before that wait -- stopping early
 * leaves the child blocked on a full pipe, which turns a working command into a timeout.
 */
public final class Shell {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long TIMEOUT_MS = 2000L;

    private Shell() {
    }

    /** First line of output, or unreadable. */
    public static Text firstLine(String... argv) {
        return run(argv, true);
    }

    /** All output, or unreadable. */
    public static Text all(String... argv) {
        return run(argv, false);
    }

    private static Text run(String[] argv, boolean firstLineOnly) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<String>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } finally {
                reader.close();
            }
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Diag.warn("shell", "timeout: " + String.join(" ", argv), null);
                return Text.unreadable();
            }
            // stderr is merged into the stream above so a failure message is available for the
            // log, which means a non-zero exit is what distinguishes "an error message" from
            // "a value". Without this, `getenforce` being denied reads as a parsable answer.
            int exit = process.exitValue();
            if (exit != 0) {
                Diag.warn("shell", "exit " + exit + ": " + String.join(" ", argv)
                        + ": " + String.join(" ", lines), null);
                return Text.unreadable();
            }
            if (lines.isEmpty()) {
                return Text.of("");
            }
            return Text.of(firstLineOnly ? lines.get(0) : String.join("\n", lines));
        } catch (Exception e) {
            Diag.warn("shell", "failed: " + String.join(" ", argv), e);
            return Text.unreadable();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
