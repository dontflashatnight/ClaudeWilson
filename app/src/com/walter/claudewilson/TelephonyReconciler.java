package com.walter.claudewilson;

import android.content.Context;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converges the telephony property surfaces after a region apply.
 *
 * <p>{@code setprop} is not a transaction: the phone process rewrites {@code gsm.*} from modem and
 * SIM state shortly after the shell returns. The UI therefore treats the active slot as the source
 * of truth and this worker re-applies an idempotent fill until both trios match, or the bounded
 * verification window ends. Radio, subscription and resume events call {@link #request(boolean)}
 * again; no polling loop runs forever.
 */
final class TelephonyReconciler {

    static final class Desired {
        final String numeric;
        final String iso;
        final String carrier;
        final int sub;

        Desired(String numeric, String iso, String carrier, int sub) {
            this.numeric = numeric;
            this.iso = iso;
            this.carrier = carrier;
            this.sub = sub;
        }
    }

    interface Source {
        Desired desired();
    }

    /** Short settle window; later events restart it, so this is not a periodic poll. */
    private static final long[] RETRY_MS = {700L, 1800L, 4000L, 8000L};
    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telephony-reconciler");
        t.setDaemon(true);
        return t;
    });

    private final Context context;
    private final Source source;
    private final Runnable changed;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;

    TelephonyReconciler(Context context, Source source, Runnable changed) {
        this.context = context.getApplicationContext();
        this.source = source;
        this.changed = changed;
    }

    /**
     * @param verify also check after a currently matching snapshot; used immediately after apply
     *               and on resume, where telephony may still rewrite the just-written value.
     */
    void request(boolean verify) {
        if (closed) {
            return;
        }
        final int token = generation.incrementAndGet();
        try {
            WORKER.execute(() -> reconcile(token, 0, verify));
        } catch (RejectedExecutionException ignored) {
            // Closing the activity can race a final event; there is nothing left to update.
        }
    }

    void close() {
        closed = true;
        generation.incrementAndGet();
        // Do not shutdown the shared executor; another process-level screen may still be alive.
    }

    private void reconcile(int token, int attempt, boolean verify) {
        if (closed || token != generation.get()) {
            return;
        }
        Desired d;
        try {
            d = source.desired();
        } catch (Throwable t) {
            return;
        }
        if (d == null || d.numeric == null || d.numeric.isEmpty()
                || d.iso == null || d.iso.isEmpty() || !RootShell.rootReady()) {
            return;
        }
        if (RootShell.preservesMobileData()) {
            // Some telephony stacks build the data APN from the SIM operator properties. They
            // must stay on the physical card; the target process reads the persona from the
            // hook instead. Reasserting the persona here re-breaks data after every apply.
            return;
        }
        int slot = PersonaPanel.activeSlotOf(context);
        DeviceSignals.refreshNow();
        boolean simMatch = simMatches(d, slot);
        boolean netMatch = netMatches(d, slot);
        boolean match = simMatch && netMatch;
        boolean wrote = false;
        if (!match) {
            if (!simMatch && d.sub >= 0) {
                // The props can be right while the carrier-config override was lost in a phone
                // restart. Re-assert that surface before filling the property list.
                RootShell.runCapture(RootShell.simReassertScript(
                        d.sub, d.iso, d.carrier == null ? "" : d.carrier));
            }
            RootShell.runCapture(SystemBuild.telephonyScript(
                    d.numeric, d.iso, d.carrier == null ? "" : d.carrier));
            wrote = true;
            DeviceSignals.refreshNow();
            changed.run();
        }
        // A force request starts a bounded verification chain even when the first read matches:
        // the phone process often rewrites the active slot just after setprop returns.
        if (match && !verify && !wrote) {
            return;
        }
        int next = attempt + 1;
        if (next < RETRY_MS.length) {
            try {
                WORKER.schedule(() -> reconcile(token, next, true),
                        RETRY_MS[attempt], TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Executor shutting down; a later event will retry.
            }
        }
    }

    private static boolean simMatches(Desired d, int slot) {
        return same(d.numeric, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.sim.operator.numeric"), slot))
                && same(d.iso, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.sim.operator.iso-country"), slot))
                && same(d.carrier == null ? "" : d.carrier, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.sim.operator.alpha"), slot));
    }

    private static boolean netMatches(Desired d, int slot) {
        return same(d.numeric, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.operator.numeric"), slot))
                && same(d.iso, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.operator.iso-country"), slot))
                && same(d.carrier == null ? "" : d.carrier, PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.operator.alpha"), slot));
    }

    private static boolean same(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }
}
