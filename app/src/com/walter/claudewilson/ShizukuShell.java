package com.walter.claudewilson;

import android.content.pm.PackageManager;

import com.walter.claudewilson.model.Output;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Non-root privileged channel through the Shizuku server (uid 2000 or 0). */
final class ShizukuShell {
    static final int ST_NO_BINDER = 0;
    static final int ST_NO_PERM = 1;
    static final int ST_READY = 2;

    private static final long TIMEOUT_MS = 20000L;
    private static volatile Method cachedNewProcess;

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

    private static Method resolveNewProcess() throws NoSuchMethodException {
        Method method = cachedNewProcess;
        if (method != null) {
            return method;
        }
        try {
            method = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
        } catch (NoSuchMethodException e) {
            method = Shizuku.class.getMethod(
                    "newProcess", String[].class, String[].class, String.class);
        }
        method.setAccessible(true);
        cachedNewProcess = method;
        return method;
    }

    static String run(final String sh, final int max, long timeoutMs) {
        final String[] result = new String[1];
        final Process[] holder = new Process[1];
        Thread worker = new Thread(() -> {
            Process process = null;
            try {
                if (!Shizuku.pingBinder()) {
                    result[0] = "exc:shizuku-no-binder";
                    return;
                }
                Method method = resolveNewProcess();
                Object created = method.invoke(
                        null, new String[]{"sh", "-c", "exec 2>&1\n" + sh}, null, null);
                if (!(created instanceof Process)) {
                    result[0] = "exc:shizuku-null-process";
                    return;
                }
                process = (Process) created;
                holder[0] = process;
                String out = readAll(process.getInputStream()).trim();
                int rc = process.waitFor();
                if (out.isEmpty()) {
                    result[0] = "rc=" + rc;
                } else if (rc != 0) {
                    result[0] = Output.bounded(out + "\nrc=" + rc, max);
                } else {
                    result[0] = Output.bounded(out, max);
                }
            } catch (Throwable t) {
                Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                if (cause instanceof SecurityException) {
                    result[0] = "exc:shizuku-security:" + safeMessage(cause);
                } else if (cause instanceof IllegalStateException) {
                    result[0] = "exc:shizuku-state:" + safeMessage(cause);
                } else {
                    result[0] = "exc:" + cause;
                }
            } finally {
                closeProcessStreams(process);
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
            closeProcessStreams(process);
        }
        return "timeout: no answer in " + timeoutMs + "ms";
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
            // already closed
        }
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
