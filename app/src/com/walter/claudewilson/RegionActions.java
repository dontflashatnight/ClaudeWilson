package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;

import com.walter.claudewilson.model.SimPolicy;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.util.ArrayList;
import java.util.List;

/** Region backup/restore and subscription reads, independent of screen widgets. */
final class RegionActions {
    private final Context context;
    private final SharedPreferences preferences;

    RegionActions(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("apply", 0);
    }

    void saveOriginal(String userId, TargetSpec target) {
        int active = activeSub();
        String num = nn(DeviceSignals.getprop("gsm.sim.operator.numeric"));
        String iso = nn(DeviceSignals.getprop("gsm.sim.operator.iso-country"));
        String alpha = nn(DeviceSignals.getprop("gsm.sim.operator.alpha"));
        String[] sub = subscriptionIdentity();
        // A per-slot property list ("sg,nl") or an empty value is not a baseline a revert can
        // restore from. The subscription record names the card the device is actually using.
        // On stacks that resolve the data APN from the SIM operator properties, the properties
        // may already hold a persona, so the card record is the only trustworthy baseline.
        if (sub != null && (RootShell.preservesMobileData()
                || unusable(num) || unusable(iso) || unusable(alpha))) {
            num = sub[0];
            iso = sub[1];
            alpha = sub[2];
        }
        // MCC is the country of the card. The iso prop and the subscription's countryIso can be
        // stale (observed a China MCC next to "tw"); restoring such a pair would recreate the
        // contradiction.
        iso = mccIso(num, iso);
        String display = sub == null ? "" : sub[3];
        int source = -1;
        if (sub != null && sub[4] != null && sub[4].matches("\\d+")) {
            source = Integer.parseInt(sub[4]);
        }
        String targetLocale = appLocales(target == null ? null : target.packageName(), userId);
        String browserLocale = appLocales(RootShell.browserPackage(context), userId);
        EnvSession.captureBaseline(context, num, iso, alpha, display, source, active,
                target == null ? TargetRegistry.CLAUDE.id() : target.id(), userId,
                targetLocale, browserLocale);
    }

    /** Per-app locale list, or "" when the app follows the system. */
    private String appLocales(String pkg, String userId) {
        if (pkg == null || !com.walter.claudewilson.model.ShellQuote.packageName(pkg)) {
            return "";
        }
        String userOpt = userId != null
                && com.walter.claudewilson.model.ShellQuote.numeric(userId)
                && !"0".equals(userId) ? " --user " + userId : "";
        String out = RootShell.run("cmd locale get-app-locales " + pkg + userOpt, 256);
        int open = out.indexOf('[');
        int close = out.lastIndexOf(']');
        return open >= 0 && close > open ? out.substring(open + 1, close).trim() : "";
    }

    private String setAppLocales(String pkg, String locales, String userId) {
        if (pkg == null || !com.walter.claudewilson.model.ShellQuote.packageName(pkg)) {
            return "";
        }
        boolean nonZeroUser = userId != null
                && com.walter.claudewilson.model.ShellQuote.numeric(userId)
                && !"0".equals(userId);
        String pmUserOpt = nonZeroUser ? "--user " + userId + " " : "";
        String locUserOpt = nonZeroUser ? " --user " + userId : "";
        String localeArg = (locales == null || locales.isEmpty())
                ? " --locales empty"
                : " --locales " + com.walter.claudewilson.model.ShellQuote.quote(locales);
        String call = "cmd locale set-app-locales " + pkg + locUserOpt + localeArg;
        String wrapped = RootShell.wrappedLocaleCall("locale-restore", call, RootShell.rootReady());
        String command = "if pm path " + pmUserOpt + pkg + " >/dev/null 2>&1; then "
                + wrapped + "; fi";
        return RootShell.run(command, 256);
    }

    /** A value that cannot be restored as-is: empty, or a per-slot comma list. */
    private static boolean unusable(String value) {
        return value.isEmpty() || value.indexOf(',') >= 0;
    }

    /** The MCC country is authoritative; a stale iso prop must not become the revert baseline. */
    private String mccIso(String numeric, String fallback) {
        if (numeric == null || numeric.length() < 3) {
            return fallback;
        }
        try {
            String derived = com.walter.claudewilson.platform.TablesLoader
                    .load(context).mccToIso().get(numeric.substring(0, 3));
            return derived == null || derived.isEmpty() ? fallback : derived;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * {mccMnc, iso, carrierName, displayName, nameSource} of the default data subscription, or
     * null when it is unreadable.
     *
     * <p>The subscription record is the only per-subscription identity on a shell channel: the
     * property area holds a per-slot list there, and TelephonyManager answers with the overridden
     * values once a carrier-config override is in place. The display name is the one Settings'
     * SIM manager renders; it is not derived from the carrier-config override.
     */
    String[] subscriptionIdentity() {
        try {
            android.telephony.SubscriptionManager sm =
                    context.getSystemService(android.telephony.SubscriptionManager.class);
            List<android.telephony.SubscriptionInfo> subs =
                    sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                return null;
            }
            int wanted = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            android.telephony.SubscriptionInfo chosen = subs.get(0);
            for (android.telephony.SubscriptionInfo candidate : subs) {
                if (candidate.getSubscriptionId() == wanted) {
                    chosen = candidate;
                    break;
                }
            }
            String mcc = chosen.getMccString();
            String mnc = chosen.getMncString();
            String iso = chosen.getCountryIso();
            CharSequence name = chosen.getCarrierName();
            String label = name == null ? "" : name.toString();
            // Telephony appends a service suffix to the display name ("BetterRoaming | 没有服务").
            // It is not part of the carrier name and must not be written back as an override.
            int suffix = label.indexOf(" | ");
            if (suffix > 0) {
                label = label.substring(0, suffix);
            }
            CharSequence display = chosen.getDisplayName();
            String shown = display == null ? "" : display.toString();
            int shownSuffix = shown.indexOf(" | ");
            if (shownSuffix > 0) {
                shown = shown.substring(0, shownSuffix);
            }
            int source = CarrierCommands.nameSourceUserInput();
            try {
                java.lang.reflect.Method m = android.telephony.SubscriptionInfo.class
                        .getMethod("getNameSource");
                source = (Integer) m.invoke(chosen);
            } catch (Throwable ignored) {
                // Hidden API: the display name is still usable, the source falls back to user.
            }
            // If displayName was previously overwritten with a simulated carrier via USER_INPUT
            // (e.g. "docomo" on a "中国联通" SIM), fall back to the SIM's true carrierName so
            // revert restores the real carrier instead of preserving a stale persona label.
            String cleanLabel = label.trim();
            String cleanShown = shown.trim();
            if (source == CarrierCommands.nameSourceUserInput()
                    && !cleanLabel.isEmpty() && !cleanShown.equals(cleanLabel)) {
                cleanShown = cleanLabel;
                source = 0;
            }
            return new String[]{
                    mcc == null || mnc == null ? "" : mcc + mnc,
                    iso == null ? "" : iso,
                    cleanLabel,
                    cleanShown,
                    String.valueOf(source)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Evaluates whether the live physical SIM is outside the persisted restricted-regions catalog
     * and safe to preserve untouched (including dual-SIM peer-slot checks).
     */
    static SimPolicy.Decision evaluateRealSim(Context ctx, String targetId,
                                              boolean preserveEnabled) {
        String activeOp = "";
        String activeIso = "";
        String activeCarrier = "";
        String activeDisplay = "";
        List<String> allOps = new ArrayList<String>();
        List<String> allIsos = new ArrayList<String>();
        try {
            android.telephony.SubscriptionManager sm =
                    ctx.getSystemService(android.telephony.SubscriptionManager.class);
            List<android.telephony.SubscriptionInfo> subs =
                    sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs != null && !subs.isEmpty()) {
                int wanted = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
                android.telephony.SubscriptionInfo chosen = subs.get(0);
                for (android.telephony.SubscriptionInfo s : subs) {
                    String mcc = s.getMccString();
                    String mnc = s.getMncString();
                    if (mcc != null && mnc != null && !mcc.isEmpty() && !mnc.isEmpty()) {
                        allOps.add(mcc + mnc);
                    }
                    String iso = s.getCountryIso();
                    if (iso != null && !iso.isEmpty()) {
                        allIsos.add(iso);
                    }
                    if (s.getSubscriptionId() == wanted) {
                        chosen = s;
                    }
                }
                String mcc = chosen.getMccString();
                String mnc = chosen.getMncString();
                if (mcc != null && mnc != null && !mcc.isEmpty() && !mnc.isEmpty()) {
                    activeOp = mcc + mnc;
                }
                activeIso = nn(chosen.getCountryIso());
                CharSequence cName = chosen.getCarrierName();
                CharSequence dName = chosen.getDisplayName();
                activeCarrier = cleanLocalizedCarrier(cName == null ? "" : cName.toString());
                activeDisplay = cleanLocalizedCarrier(dName == null ? "" : dName.toString());
            }
        } catch (Throwable ignored) {
        }
        // Inspect live per-slot SIM props as well so a restricted peer slot cannot slip through
        // if SubscriptionManager is unreadable or briefly lags behind radio state.
        String rawOps = nn(DeviceSignals.getprop("gsm.sim.operator.numeric"));
        String rawIsos = nn(DeviceSignals.getprop("gsm.sim.operator.iso-country"));
        for (String part : rawOps.split(",")) {
            String t = part.trim();
            if (!t.isEmpty() && !allOps.contains(t)) {
                allOps.add(t);
            }
        }
        for (String part : rawIsos.split(",")) {
            String t = part.trim();
            if (!t.isEmpty() && !allIsos.contains(t)) {
                allIsos.add(t);
            }
        }
        // Only override live SubscriptionInfo with EnvSession.Baseline when the active session
        // actually wrote spoofed SIM properties. In preserve-SIM mode (including Multi-IMSI eSIMs
        // that switch IMSI under the same subId), live SubscriptionInfo stays the source of truth.
        EnvSession.Baseline base = EnvSession.baseline(ctx);
        int currentSub = new RegionActions(ctx).activeSub();
        if (EnvSession.simSpoofed(ctx) && base != null && base.sub >= 0 && base.sub == currentSub
                && base.simNum != null && !base.simNum.isEmpty()) {
            activeOp = base.simNum;
            activeIso = nn(base.simIso);
            activeCarrier = cleanLocalizedCarrier(nn(base.simAlpha));
            activeDisplay = cleanLocalizedCarrier(nn(base.simDisplay));
            if (!allOps.contains(base.simNum)) {
                allOps.add(base.simNum);
            }
            if (!activeIso.isEmpty() && !allIsos.contains(activeIso)) {
                allIsos.add(activeIso);
            }
        }
        if (activeOp.isEmpty()) {
            if (base != null && base.simNum != null && !base.simNum.isEmpty()) {
                activeOp = base.simNum;
                activeIso = nn(base.simIso);
                activeCarrier = cleanLocalizedCarrier(nn(base.simAlpha));
                activeDisplay = cleanLocalizedCarrier(nn(base.simDisplay));
            } else {
                int slot = PersonaPanel.activeSlotOf(ctx);
                activeOp = nn(PersonaPanel.slotValue(rawOps, slot));
                activeIso = nn(PersonaPanel.slotValue(rawIsos, slot));
                activeCarrier = cleanLocalizedCarrier(nn(PersonaPanel.slotValue(
                        DeviceSignals.getprop("gsm.sim.operator.alpha"), slot)));
            }
        }
        return SimPolicy.evaluate(
                com.walter.claudewilson.platform.TablesLoader.load(ctx),
                targetId, preserveEnabled,
                activeOp, activeIso, activeCarrier, activeDisplay,
                allOps, allIsos);
    }

    /** Strips localized no-service placeholders before passing to pure-ASCII SimPolicy. */
    private static String cleanLocalizedCarrier(String raw) {
        String s = raw == null ? "" : raw.trim();
        int bar = s.indexOf(" | ");
        if (bar > 0) {
            s = s.substring(0, bar).trim();
        }
        if ("没有服务".equals(s) || "无服务".equals(s) || "仅限紧急呼叫".equals(s)
                || "僅限緊急通話".equals(s) || "無服務".equals(s)
                || "圏外".equals(s) || "緊急通報のみ".equals(s)) {
            return "";
        }
        return s;
    }

    private static String nn(String v) { return v == null ? "" : v; }

    Restore prepareRestore(String currentUser) {
        EnvSession.Baseline b = EnvSession.baseline(context);
        // The apply targeted one subscription; the revert must hit that one even if the user has
        // switched the active SIM since.
        int sub = b != null && b.sub >= 0 ? b.sub : activeSub();
        String tz = b == null || b.tz == null || b.tz.isEmpty() ? homeZoneFromSim() : b.tz;
        String appliedUser = b == null || b.user == null ? currentUser : b.user;
        TargetSpec appliedTarget = TargetRegistry.byId(b == null ? null : b.target);
        String[] sim = b == null || b.simNum == null || b.simNum.isEmpty() ? liveSim()
                : new String[]{b.simNum, b.simIso, b.simAlpha, b.simDisplay,
                        String.valueOf(b.simSource)};
        return new Restore(sub, tz, b == null ? 1 : b.autoTz, b == null ? 0L : b.at,
                appliedUser == null || appliedUser.isEmpty() ? currentUser : appliedUser,
                sim, appliedTarget);
    }

    final class Restore {
        private final int sub;
        private final String zone;
        private final int auto;
        private final long savedAt;
        private final String appliedUser;
        private final String[] sim;
        private final TargetSpec target;

        Restore(int sub, String zone, int auto, long savedAt, String appliedUser,
                String[] sim, TargetSpec target) {
            this.sub = sub; this.zone = zone; this.auto = auto;
            this.savedAt = savedAt; this.appliedUser = appliedUser; this.sim = sim;
            this.target = target;
        }

        /** The user the apply targeted; reverting uses this, not whatever is typed now. */
        String appliedUser() {
            return appliedUser;
        }

        /** Age of the timezone snapshot, or -1 when none was stored. */
        long snapshotAgeMinutes() {
            return savedAt <= 0 ? -1 : (System.currentTimeMillis() - savedAt) / 60000L;
        }

        Result execute(String userId) {
            if (!com.walter.claudewilson.model.ShellQuote.numeric(userId)) {
                return new Result("refused: invalid user id", false, false, false);
            }
            String revertTargetPkg = target != null
                    ? target.packageName() : Targets.current().packageName();
            com.walter.claudewilson.model.Outcome res =
                    RootShell.revert(sub, zone, auto, sim,
                            RootShell.browserPackage(context),
                            revertTargetPkg,
                            userId);
            boolean root = RootShell.rootReady();
            boolean aidFailed = false;
            boolean fwmuFailed = false;
            boolean localeFailed = false;
            String aid = "";
            String fwmu = "";
            String localeOut = "";
            EnvSession.Baseline baseline = EnvSession.baseline(context);
            if (baseline != null) {
                String targetPkg = target == null ? null : target.packageName();
                // Empty baseline locales were already cleared by RootShell.revert(); only restore
                // non-empty user-configured per-app locales here.
                String tOut = (baseline.targetLocale != null && !baseline.targetLocale.isEmpty())
                        ? setAppLocales(targetPkg, baseline.targetLocale, userId) : "";
                String bOut = (baseline.browserLocale != null && !baseline.browserLocale.isEmpty())
                        ? setAppLocales(RootShell.browserPackage(context),
                                baseline.browserLocale, userId) : "";
                localeFailed = (!tOut.isEmpty() && RootShell.failed(tOut))
                        || (!bOut.isEmpty() && RootShell.failed(bOut));
                localeOut = tOut.isEmpty() ? bOut
                        : bOut.isEmpty() ? tOut : tOut + "\n" + bOut;
            }
            if (root) {
                // This probe is one shell process. The old revert started `su` unconditionally,
                // even when no SSAID had ever been touched, and a refused/missing su then turned
                // a successful region reset into a failed one.
                String state = RootShell.run("a=no; [ -s /data/local/tmp/ssaid_orig_"
                        + userId + " ] && a=yes; f=no; [ -f /data/local/tmp/fwmu_bumped ]"
                        + " && f=yes; echo AID=$a FWMU=$f");
                boolean hasAid = state.contains("AID=yes");
                boolean hasFwmu = state.contains("FWMU=yes");
                if (hasAid) {
                    // Only the actual restore needs root; it is rare (AID was explicitly
                    // rewritten for this space), not part of every region reset.
                    aid = RootShell.runRoot(
                            com.walter.claudewilson.model.RootScript.restoreSsaid(
                                    userId, target.packageName()));
                    aidFailed = RootShell.failed(aid);
                } else {
                    aid = "aid-skip";
                }
                if (hasFwmu) {
                    fwmu = RootShell.runRoot("setprop fw.max_users \"\""
                            + " && rm -f /data/local/tmp/fwmu_bumped"
                            + " && echo fwmu-clean");
                    fwmuFailed = RootShell.failed(fwmu);
                } else {
                    fwmu = "fwmu-clean";
                }
            } else {
                aid = "SKIP:aid needs-root";
                fwmu = "SKIP:fwmu needs-root";
            }
            boolean ok = res.ok() && !aidFailed && !fwmuFailed && !localeFailed;
            // Only a carrier override that was actually cleared needs the phone process to
            // re-read the card. A ROM that denies shell `overrideConfig` skips both the apply and
            // the clear, so resetting must not restart telephony for nothing.
            boolean needsPhoneReload = res.ok() && !root && sub >= 0
                    && !res.skippedSteps().contains("carrier-clear");
            String output = res.output() + (localeOut.isEmpty() ? "" : "\n" + localeOut)
                    + (aid.isEmpty() ? "" : "\n" + aid)
                    + (fwmu.isEmpty() ? "" : "\n" + fwmu);
            return new Result(output, aid.contains("aid-restored:"), ok, needsPhoneReload);
        }

        void clearBackup() {
            preferences.edit().remove("orig_tz").remove("orig_autotz")
                    .remove("orig_tz_at").remove("orig_user")
                    .remove("orig_sim_num").remove("orig_sim_iso")
                    .remove("orig_sim_alpha").remove("applied_sub")
                    .remove("applied_target")
                    .remove("applied_build_sig").apply();
        }
    }

    static final class Result {
        final String output;
        final boolean aidRestored;
        /** False when any step reported a failure; the caller keeps its backup in that case. */
        final boolean ok;
        /** Shell channel only: the caller should let the phone process re-read the card. */
        final boolean needsPhoneReload;
        Result(String output, boolean aidRestored, boolean ok, boolean needsPhoneReload) {
            this.output = output; this.aidRestored = aidRestored; this.ok = ok;
            this.needsPhoneReload = needsPhoneReload;
        }
    }

    /**
     * Real SIM identity, used when the snapshot is missing (for example right after a package
     * rename). Clearing the props is not a restore: the framework only repopulates them on the
     * next SIM event, so the card would show blanks until a reboot.
     */
    private String[] liveSim() {
        try {
            android.telephony.SubscriptionManager sm =
                    context.getSystemService(android.telephony.SubscriptionManager.class);
            java.util.List<android.telephony.SubscriptionInfo> l =
                    sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (l == null || l.isEmpty()) return null;
            // The enabled card is the one the device actually runs on: prefer the default data
            // subscription, and never skip the eSIM -- on this device the eSIM is the only card.
            int data = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            android.telephony.SubscriptionInfo best = null;
            for (android.telephony.SubscriptionInfo si : l) {
                if (si.getSubscriptionId() == data) { best = si; break; }
            }
            if (best == null) {
                for (android.telephony.SubscriptionInfo si : l) {
                    if (si.getSimSlotIndex() >= 0) { best = si; break; }
                }
            }
            if (best == null) best = l.get(0);
            String mcc = best.getMccString();
            String mnc = best.getMncString();
            CharSequence name = best.getCarrierName();
            CharSequence display = best.getDisplayName();
            int source = CarrierCommands.nameSourceUserInput();
            try {
                java.lang.reflect.Method m = android.telephony.SubscriptionInfo.class
                        .getMethod("getNameSource");
                source = (Integer) m.invoke(best);
            } catch (Throwable ignored) {
                // Hidden API: the display name is still usable, the source falls back to user.
            }
            return new String[]{mcc == null || mnc == null ? "" : mcc + mnc,
                    best.getCountryIso() == null ? "" : best.getCountryIso(),
                    name == null ? "" : name.toString(),
                    display == null ? "" : display.toString(),
                    String.valueOf(source)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Physical SIM's home timezone; embedded subscriptions are skipped. */
    private String homeZoneFromSim() {
        try {
            android.telephony.SubscriptionManager sm =
                    context.getSystemService(android.telephony.SubscriptionManager.class);
            if (sm == null) return null;
            List<android.telephony.SubscriptionInfo> l = allOrActiveSubscriptions(sm);
            if (l == null) return null;
            int bestMcc = -1;
            int score = -1;
            for (android.telephony.SubscriptionInfo si : l) {
                if (si.isEmbedded()) continue;
                int mcc = parsePositiveInt(si.getMccString());
                if (mcc <= 0) continue;
                int s = si.getSubscriptionId()
                        + (si.getSimSlotIndex() >= 0 ? 100000 : 0);
                if (s > score) { score = s; bestMcc = mcc; }
            }
            if (bestMcc <= 0) return null;
            String iso = Regions.isoForMcc(bestMcc);
            return iso == null ? null : Regions.primaryZone(iso);
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<android.telephony.SubscriptionInfo> allOrActiveSubscriptions(
            android.telephony.SubscriptionManager sm) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                java.lang.reflect.Method m = android.telephony.SubscriptionManager.class
                        .getMethod("getAllSubscriptionInfoList");
                Object list = m.invoke(sm);
                if (list instanceof List) {
                    return (List<android.telephony.SubscriptionInfo>) list;
                }
            } catch (Throwable ignored) {
                // Fall back to active subscriptions when getAllSubscriptionInfoList is unavailable.
            }
        }
        return sm.getActiveSubscriptionInfoList();
    }

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isEmpty()) return -1;
        try {
            int val = Integer.parseInt(raw.trim());
            return val > 0 ? val : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * The default data subscription, or -1.
     *
     * <p>One SIM is the supported shape; the carrier override is written per subscription, so a
     * stale id left over from a previous apply would be an override nothing ever clears.
     */
    int activeSub() {
        try {
            android.telephony.SubscriptionManager sm =
                    context.getSystemService(android.telephony.SubscriptionManager.class);
            if (sm == null) return -1;
            // The default data subscription is the one the target actually runs on. Taking
            // list.get(0) picked the disabled physical SIM on a device whose only enabled SIM
            // is the eSIM, so the carrier override landed on a slot that is switched off.
            int data = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            List<android.telephony.SubscriptionInfo> l = sm.getActiveSubscriptionInfoList();
            if (l != null) {
                for (android.telephony.SubscriptionInfo si : l) {
                    if (si.getSubscriptionId() == data) return data;
                }
            }
            if (data >= 0) return data;
            if (l == null || l.isEmpty()) return -1;
            for (android.telephony.SubscriptionInfo si : l) {
                if (si.getSimSlotIndex() >= 0) return si.getSubscriptionId();
            }
            return l.get(0).getSubscriptionId();
        } catch (Throwable t) { return -1; }
    }
}
