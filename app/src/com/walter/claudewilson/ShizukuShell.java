package com.walter.claudewilson;

import android.content.pm.PackageManager;

import com.walter.claudewilson.model.Output;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/** Non-root privileged channel through the Shizuku server (uid 2000 or 0). */
final class ShizukuShell {
    static final int ST_NO_BINDER = 0;
    static final int ST_NO_PERM = 1;
    static final int ST_READY = 2;

    private static final long TIMEOUT_MS = 20000L;

    private ShizukuShell() {
    }

    static int state() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                return ST_NO_BINDER;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? ST_READY : ST_NO_PERM;
        } catch (Throwable t) {
            return ST_NO_BINDER;
        }
    }

    static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
            // Shizuku not running; the next binder event re-probes.
        }
    }

    static boolean ready() {
        return state() == ST_READY;
    }

    static String run(String sh, int max) {
        return run(sh, max, TIMEOUT_MS);
    }

    static String run(final String sh, final int max, long timeoutMs) {
        final String[] result = new String[1];
        final Process[] holder = new Process[1];
        Thread worker = new Thread(() -> {
            try {
                java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                method.setAccessible(true);
                ShizukuRemoteProcess remote = (ShizukuRemoteProcess) method.invoke(
                        null, new String[]{"sh", "-c", sh}, null, null);
                Process process = remote;
                holder[0] = process;
                String out = readAll(process.getInputStream()).trim();
                int rc = process.waitFor();
                result[0] = out.isEmpty() ? "rc=" + rc : Output.bounded(out, max);
            } catch (Throwable t) {
                result[0] = "exc:" + t;
            }
        }, "shizuku-run");
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
                process.destroy();
            } catch (Throwable ignored) {
                // process already gone
            }
        }
        return "timeout: no answer in " + timeoutMs + "ms";
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }
}
