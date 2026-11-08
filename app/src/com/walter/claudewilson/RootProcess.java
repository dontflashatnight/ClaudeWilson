package com.walter.claudewilson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import com.walter.claudewilson.model.Output;

/**
 * Runs one command through the device's {@code su} binary.
 *
 * <p>This is the root-only transport. It deliberately does not keep a shell open: the commands
 * here are short-lived and the activity already calls them from a worker thread. A long-lived
 * shell would be faster, but would also need its own framing, reconnection and cleanup; the
 * current call volume does not justify that.
 */
final class RootProcess {
    private static final long TIMEOUT_MS = 20000L;

    private RootProcess() {}

    static String run(String sh, int max) {
        return run(sh, max, TIMEOUT_MS);
    }

    static String run(String sh, int max, long timeoutMs) {
        final Process[] holder = new Process[1];
        final String[] result = new String[1];
        final Throwable[] error = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder("/system/bin/su", "-c", sh);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                holder[0] = process;
                String out = readAll(process.getInputStream()).trim();
                int rc = process.waitFor();
                result[0] = out.isEmpty() ? "rc=" + rc : Output.bounded(out, max);
            } catch (Throwable t) {
                error[0] = t;
                result[0] = "exc:" + t;
                com.walter.claudewilson.platform.Diag.warn("root", "su start failed", t);
            }
        }, "root-run");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (result[0] != null) {
            return result[0];
        }
        Process process = holder[0];
        if (process != null) {
            try {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                // the process may already be gone
            }
        }
        return "timeout: no answer in " + timeoutMs + "ms";
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
