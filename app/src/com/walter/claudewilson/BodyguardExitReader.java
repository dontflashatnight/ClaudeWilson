package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.BodyguardEventCodec;
import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.platform.Diag;

/**
 * Zero-intrusion harvester for Bodyguard runtime incidents reported via Android 11+
 * {@code ApplicationExitInfo.getProcessStateSummary()}.
 *
 * <p>Runs {@code Bridge exitinfo <pkg> <userId>} through the root channel, parses any
 * {@code CWBG1|...} frames, records deduplicated incidents into {@link LedgerDb}, and locks the
 * target in {@link BodyguardStore} when a new {@code KILLED} event is observed.
 */
final class BodyguardExitReader {
    private static final long MIN_INTERVAL_MS = 20000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_HARVEST =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private BodyguardExitReader() {
    }

    /** Harvests Bodyguard exit-info summaries for {@code spec}; returns count of newly stored events. */
    static int harvest(Context context, TargetSpec spec, String userId) {
        return harvest(context, spec, userId, false);
    }

    /**
     * Harvests Bodyguard exit-info summaries for {@code spec}. When {@code force} is true (e.g.
     * foreground resume or pull-to-refresh), bypasses the 20s poll throttle so an immediate
     * {@code system_server} anti-restart lock is surfaced in the UI without delay.
     */
    static int harvest(Context context, TargetSpec spec, String userId, boolean force) {
        if (context == null || spec == null || !RootShell.rootReady()
                || !ShellQuote.packageName(spec.packageName())) {
            return 0;
        }
        String u = (userId != null && ShellQuote.numeric(userId)) ? userId : "0";
        if (com.walter.claudewilson.model.BodyguardPolicy.MODE_OFF.equals(
                BodyguardStore.mode(context, u))) {
            return 0;
        }
        String key = spec.id() + ":" + u;
        long now = android.os.SystemClock.elapsedRealtime();
        Long prev = LAST_HARVEST.get(key);
        if (!force && prev != null && (now - prev) < MIN_INTERVAL_MS) {
            return 0;
        }
        LAST_HARVEST.put(key, now);
        String selfPkg = context.getApplicationContext().getPackageName();
        if (!ShellQuote.packageName(selfPkg)) {
            return 0;
        }
        String evFile = com.walter.claudewilson.model.IpGuardPolicy.netEventPath(spec.id());
        String lockFile = com.walter.claudewilson.model.IpGuardPolicy.LOCK_DIR + "/" + spec.id() + ".lock";
        String unlockFile = com.walter.claudewilson.model.IpGuardPolicy.LOCK_DIR + "/" + spec.id() + ".unlock";
        String cmd = "APK=$(pm path " + selfPkg + " | cut -d: -f2); "
                + "[ -n \"$APK\" ] && app_process -Djava.class.path=$APK /system/bin "
                + "com.walter.claudewilson.Bridge exitinfo " + spec.packageName() + " " + u
                + " 2>/dev/null || true; "
                + "[ -f " + evFile + " ] && cat " + evFile + " 2>/dev/null || true; "
                + "LT=0; UT=0; "
                + "[ -f " + lockFile + " ] && LT=$(cat " + lockFile + " 2>/dev/null); "
                + "[ -f " + unlockFile + " ] && UT=$(cat " + unlockFile + " 2>/dev/null); "
                + "echo \"CWLOCK $LT $UT\"";
        String out = RootShell.run(cmd, 8192, 6000L);
        if (out == null || out.isEmpty()) {
            return 0;
        }
        int ingested = 0;
        long lockTs = 0L;
        long unlockTs = 0L;
        boolean sawLockLine = false;
        boolean lockedNowByEvent = false;
        final boolean killMode = com.walter.claudewilson.model.BodyguardPolicy.MODE_BLOCK_KILL
                .equals(BodyguardStore.mode(context, u));
        LedgerDb db = LedgerDb.get(context);
        java.util.List<LedgerDb.Incident> before = db.loadIncidents(spec.id(), u, false);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (LedgerDb.Incident inc : before) {
            seen.add(inc.occurredAt + "|" + inc.signalKey);
        }
        String latestKillKey = "";
        long latestKillTs = 0L;
        for (String line : out.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("CWLOCK ")) {
                sawLockLine = true;
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3) {
                    try { lockTs = Long.parseLong(parts[1]); } catch (NumberFormatException ignored) {}
                    try { unlockTs = Long.parseLong(parts[2]); } catch (NumberFormatException ignored) {}
                }
                continue;
            }
            if (trimmed.isEmpty() || !trimmed.contains("CWBG1|")) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            long ts;
            try {
                ts = Long.parseLong(trimmed.substring(0, tab).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            BodyguardEventCodec.Event ev = BodyguardEventCodec.decode(trimmed.substring(tab + 1));
            if (ev == null) {
                continue;
            }
            String dedup = ts + "|" + ev.signalKey;
            boolean isNew = !seen.contains(dedup);
            db.recordIncident(spec.id(), u, ts, ev.version, ev.gate,
                    ev.signalKey, ev.callerShort, ev.action);
            if ("KILLED".equalsIgnoreCase(ev.action) && ts >= latestKillTs) {
                latestKillTs = ts;
                latestKillKey = ev.signalKey;
            }
            if (isNew) {
                seen.add(dedup);
                ingested++;
                if (killMode && "KILLED".equalsIgnoreCase(ev.action)) {
                    lockedNowByEvent = true;
                    BodyguardStore.setLocked(context, spec.id(), true);
                    BodyguardStore.setLockReason(context, spec.id(), ev.signalKey);
                    new RootActions(context).syncBodyguardLock(spec, true);
                }
                Diag.info("bodyguard", "harvested " + spec.id() + " " + ev.action
                        + " " + ev.gate + " " + ev.signalKey);
            }
        }
        boolean diskLocked = com.walter.claudewilson.model.BodyguardPolicy.isLockEffective(
                0L, lockTs, unlockTs);
        if (killMode && diskLocked && !BodyguardStore.isLocked(context, spec.id())) {
            BodyguardStore.setLocked(context, spec.id(), true);
            if (!latestKillKey.isEmpty()) {
                BodyguardStore.setLockReason(context, spec.id(), latestKillKey);
            }
            ingested++;
        } else if (sawLockLine && !lockedNowByEvent && (!killMode || !diskLocked)
                && BodyguardStore.isLocked(context, spec.id())) {
            // Keep UI lock banner strictly aligned with real on-disk lock enforcement so the
            // banner can never claim "process locked" when the target app is actually launchable.
            BodyguardStore.setLocked(context, spec.id(), false);
            ingested++;
        }
        return ingested;
    }
}
