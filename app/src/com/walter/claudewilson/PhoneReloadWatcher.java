package com.walter.claudewilson;

import com.walter.claudewilson.platform.Diag;

/**
 * Makes a shell-channel carrier reset visible without the six-second dead wait.
 *
 * <p>{@code ccclear} removes the override, but the platform keeps rendering the last overridden
 * name until the phone process re-reads the card. A shell uid cannot write
 * {@code gsm.sim.operator.*}, so the only shell path is letting the phone process restart itself
 * with {@code am crash com.android.phone}. The measured restart is ~1s, not 6s; the wait used to
 * be a fixed {@code sleep 6} inside the revert transaction, which blocked every reset for the
 * worst-case time even when the process came back immediately.
 *
 * <p>The whole wait is one shell script on a daemon thread: poll the pid, then the restored
 * property, and return as soon as both are observed. The UI is never blocked and no callback is
 * assumed to be on a particular thread.
 */
final class PhoneReloadWatcher {

    private static final Object LOCK = new Object();
    private static volatile boolean running;

    private PhoneReloadWatcher() {}

    static boolean running() {
        return running;
    }

    /** Starts a reload if none is in flight. No-op on the root channel or without root. */
    static void start() {
        synchronized (LOCK) {
            if (running) {
                return;
            }
            if (RootShell.state() != RootShell.ST_READY || RootShell.rootReady()) {
                // Root restores the properties directly; there is nothing to re-read.
                return;
            }
            running = true;
        }
        new Thread(() -> {
            try {
                String out = RootShell.run(script());
                Diag.info("phone-reload", out);
            } finally {
                running = false;
                synchronized (LOCK) {
                    LOCK.notifyAll();
                }
            }
            DeviceSignals.refresh();
            ScreenUpdates.post(screen -> screen.startScan(true));
        }, "phone-reload").start();
    }

    /**
     * A worker that is about to write the SIM surface waits for an in-flight reload. Bounded: a
     * stuck phone process must not turn into a stuck apply, and it runs off the UI thread.
     */
    static void awaitQuiet(long maxMs) {
        if (!running) {
            return;
        }
        long deadline = System.currentTimeMillis() + Math.max(0, maxMs);
        synchronized (LOCK) {
            while (running) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                try {
                    LOCK.wait(Math.min(left, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * One process: record the pid, crash the phone app, wait for a new pid, then wait for the SIM
     * name to come back. Both loops terminate early; the caps are 8s each only for a wedged
     * telephony stack.
     */
    private static String script() {
        return "old=$(pidof com.android.phone); "
                + "am crash com.android.phone >/dev/null 2>&1; "
                + "i=0; while [ $i -lt 80 ]; do "
                + "new=$(pidof com.android.phone); "
                + "if [ -n \"$new\" ] && [ \"$new\" != \"$old\" ]; then break; fi; "
                + "sleep 0.1; i=$((i+1)); done; "
                + "j=0; while [ $j -lt 80 ]; do "
                + "a=$(getprop gsm.sim.operator.alpha); "
                + "if [ -n \"$a\" ]; then break; fi; "
                + "sleep 0.1; j=$((j+1)); done; "
                + "echo PHONE_RELOADED pid=$new alpha=$a";
    }
}
