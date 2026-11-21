package com.walter.claudewilson;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.Account;
import com.walter.claudewilson.model.Outcome;
import com.walter.claudewilson.model.Report;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.platform.AndroidSnapshotCollector;
import com.walter.claudewilson.platform.Diag;
import com.walter.claudewilson.rule.ProbeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.walter.claudewilson.RegionSelection.*;

/** Programmatic screen composition; Android lifecycle belongs to MainActivity. */
final class Dashboard extends UiSection {
    private final Activity activity;
    private final RegionActions regionActions;
    NetworkPanel network;
    DataCleanPanel dataClean;
    PersonaPanel personaPanel;
    private LinearLayout personaFields;
    private LinearLayout preflightHolder;
    private Report pipelineReport;
    /** Target-side check-item model; each target builds its own from target observations. */
    private volatile com.walter.claudewilson.model.RiskModel riskModel;
    private ScrollView scroller;

    private LinearLayout flagRow;
    private TextView targetStatus;
    private LinearLayout targetStatusRow;
    /** Unix-uptime deadline for the transient "close region switch" hint under the tabs. */
    private long targetHintUntil;
    /** One region tap per window; a double tap must not queue a second apply/revert. */
    private long lastRegionTap;
    /** Where the header verdict sits; the badge is inserted after the persona count is current. */
    private int verdictIndex = -1;
    private static volatile String playCountry;   // null=未查, "err:*"=失败, else iso 小写
    private static volatile boolean playBusy;
    private static volatile String applyLog;
    private static volatile boolean applyBusy;
    private static volatile boolean reverting;
    private static volatile boolean applyAwaitingGrant;
    private static volatile int pending;   // 0=none 1=apply 2=revert
    /** Serializes the shell transaction: an off tap waits for the running apply to finish. */
    private final Object txLock = new Object();
    /** One scan at a time; an older result must not overwrite a newer one. */
    private static final AtomicInteger SCAN_GENERATION = new AtomicInteger();
    /** A scan requested while one is already running; full wins over fast. */
    private boolean scanPending;
    private boolean scanPendingFast;
    /** Accounts are stable across a region switch; a fast reconcile reuses the last full read. */
    private List<Account> cachedAccounts;
    private boolean cachedAccountsReadable;
    /** Local target inventory is expensive (one init-namespace walk); full scans refresh it. */
    private TargetEvidence cachedEvidence;
    private String cachedEvidenceTarget = "";
    private int cachedEvidenceIdentity = RootShell.ID_UNKNOWN;
    private boolean renderQueued;
    private boolean upgradeCheckedThisSession;
    private final TelephonyReconciler telephony;
    private final Runnable renderTick = () -> {
        renderQueued = false;
        render();
    };

    Dashboard(Activity activity) {
        super(activity);
        this.activity = activity;
        regionActions = new RegionActions(activity);
        telephony = new TelephonyReconciler(activity, this::telephonyDesired,
                () -> ScreenUpdates.post(Dashboard::refreshPersonaCard));
    }

    void show() {
        Regions.init(this);   // region table lives in assets; load before anything reads ALL
        Targets.init(this);
        Privacy.init(this);
        RootShell.bindSelf(this);
        ScreenUpdates.attach(this);
        configureEdgeToEdge(activity.getWindow());
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        scroller = sv;
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0D191B, BG, BG}));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 36));
        sv.addView(root);
        frame.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Translucent top status-bar gradient scrim so scrolling cards never collide with clock/icons.
        final View statusScrim = new View(this);
        statusScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF20B1416, 0xCC0B1416, 0x000B1416}));
        android.widget.FrameLayout.LayoutParams scrimLp =
                new android.widget.FrameLayout.LayoutParams(-1, dp(this, 32));
        scrimLp.gravity = Gravity.TOP;
        frame.addView(statusScrim, scrimLp);

        frame.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            android.graphics.Insets bars = windowInsets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = windowInsets.getInsets(
                    android.view.WindowInsets.Type.ime());
            int bottomInset = Math.max(bars.bottom, ime.bottom);
            root.setPadding(
                    bars.left + dp(this, 20),
                    bars.top + dp(this, 20),
                    bars.right + dp(this, 20),
                    bottomInset + dp(this, 36));
            android.view.ViewGroup.LayoutParams slp = statusScrim.getLayoutParams();
            int scrimH = bars.top + dp(this, 14);
            if (slp.height != scrimH) {
                slp.height = scrimH;
                statusScrim.setLayoutParams(slp);
            }
            return android.view.WindowInsets.CONSUMED;
        });
        // Pull-to-refresh: the list follows the finger and a circular spinner rides in the gap it
        // opens above the content. The gap is a real layout height, so it needs no inset math.
        final int rest = dp(this, 60);
        final float[] startY = new float[1];
        final boolean[] armed = new boolean[1];
        sv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startY[0] = e.getY();
                    armed[0] = sv.getScrollY() == 0 && !scanning;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!armed[0]) return false;
                    float dy = e.getY() - startY[0];
                    if (dy <= 0) return false;
                    setPullHeight((int) Math.min(dy * 0.5f, rest));
                    return true;   // own the drag: the list must not scroll while pulling
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (armed[0] && pullHeight >= rest * 0.65f) {
                        rescan();
                    } else {
                        settle(false);
                    }
                    armed[0] = false;
                    return false;
                default:
                    return false;
            }
        });
        network = new NetworkPanel(this, root, () -> regionIdx >= 0
                ? Regions.ALL.get(regionIdx).iso.toUpperCase(Locale.ROOT) : null);
        dataClean = new DataCleanPanel(this, root,
                () -> regionIdx >= 0 && Targets.current().hooksEnabled(), this::rescan);
        personaPanel = new PersonaPanel(this, this::render, this::uid,
                () -> playCountry, () -> applyLog, () -> riskModel);
        personaPanel.setWorkbenchActions(new PersonaPanel.WorkbenchActions() {
            @Override public void openSpacePicker() {
                spacePicker();
            }

            @Override public void openRegionPicker() {
                openRegionPickerSheet();
            }

            @Override public void selectRegionIso(String iso) {
                selectRegionByIso(iso);
            }

            @Override public void reapplyCurrent() {
                if (regionIdx >= 0) {
                    maybeApply();
                }
            }

            @Override public boolean isApplyBusy() {
                return applyBusy;
            }

            @Override public boolean isReverting() {
                return reverting;
            }
        });
        personaPanel.setAccountSupplier(() -> cachedAccounts, () -> cachedAccountsReadable);
        activity.setContentView(frame);
        applySystemBarAppearance(activity.getWindow());
        frame.requestApplyInsets();
        // One getprop dump (~40ms) instead of one process per row in every render. Startup only:
        // the switch path refreshes this cache on a worker.
        DeviceSignals.warm();
        TimeZoneIndex.warmAsync();
        // Land on the tab chosen in the previous process. Deliberately not re-applied here:
        // writing SIM/timezone on every launch would be a surprise, so the selection comes back
        // with its recorded "applied" flag and the user can re-run it from the chip.
        restoreSelection();
        render();
        // A restored region must also restore the module config; otherwise the target would read
        // the previous region until the user taps apply again. Only publish when root is already
        // known; the root callback retries after the grant prompt.
        if (regionIdx >= 0 && RootShell.shellReady() && personaPanel != null) {
            personaPanel.publishCurrent();
        }
        if (RootShell.shellReady()) {
            checkSelfUpgradeAndSync();
        }
        // One short entrance so the first paint has a beat instead of snapping in.
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        // A locale recreation can occur between worker completion and UI delivery.
        if (!applyBusy && pending != 0) drainPending();
    }

    void onConfigurationChanged() {
        applySystemBarAppearance(activity.getWindow());
        if (scroller != null) {
            scroller.requestApplyInsets();
        }
        requestRender();
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdge(android.view.Window window) {
        if (window == null) {
            return;
        }
        window.setDecorFitsSystemWindows(false);
        android.view.WindowManager.LayoutParams lp = window.getAttributes();
        lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(lp);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT < 35) {
            clearLegacySystemBarColors(window);
        }
    }

    @SuppressWarnings("deprecation")
    private static void clearLegacySystemBarColors(android.view.Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private static void applySystemBarAppearance(android.view.Window window) {
        if (window == null) {
            return;
        }
        android.view.WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(0, lightBars);
        }
    }

    /** Opens or closes the pull gap; the spinner fades with it. */
    private void settle(boolean refreshing) {
        int rest = dp(this, 60);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(pullHeight, refreshing ? rest : 0);
        anim.setDuration(refreshing ? 140 : 220);
        anim.addUpdateListener(a -> setPullHeight((Integer) a.getAnimatedValue()));
        anim.start();
    }

    /** Single place that owns the gap height, so a re-render keeps the current state. */
    private void setPullHeight(int h) {
        pullHeight = h;
        if (pullHolder == null) return;
        android.view.ViewGroup.LayoutParams lp = pullHolder.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            pullHolder.setLayoutParams(lp);
        }
        if (pullHolder.getChildCount() > 0) {
            pullHolder.getChildAt(0).setAlpha(h > 0 ? 1f : 0f);
        }
    }
    /** Rotating arc used as the pull-to-refresh indicator. */
    private static final class PullSpinner extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF ring = new android.graphics.RectF();
        private final long start = android.os.SystemClock.uptimeMillis();

        PullSpinner(android.content.Context context) {
            super(context);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(3f * context.getResources().getDisplayMetrics().density);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(CYAN);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float inset = paint.getStrokeWidth() / 2f;
            ring.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float turn = ((android.os.SystemClock.uptimeMillis() - start) % 900L) / 900f;
            canvas.drawArc(ring, turn * 360f, 280f, false, paint);
            if (getAlpha() > 0f) postInvalidateOnAnimation();
        }
    }


    void close() {
        ScreenUpdates.detach(this);
        telephony.close();
        if (network != null) network.release();
    }

    void onRootPermission(int granted) {
        if (granted == android.content.pm.PackageManager.PERMISSION_GRANTED
                && RootShell.shellReady() && regionIdx >= 0) {
            if (applyAwaitingGrant && !regionApplied) {
                applyAwaitingGrant = false;
                if (network != null) network.autoProbe();
                doApply();
                return;
            }
            // Make the module config match the restored region without re-running the whole
            // apply (which would rewrite timezone/locale as a side effect of opening the app).
            render();
            if (personaPanel != null) personaPanel.publishCurrent();
        } else {
            requestRender();
        }
        if (granted == android.content.pm.PackageManager.PERMISSION_GRANTED
                && RootShell.shellReady()) {
            checkSelfUpgradeAndSync();
        }
        // The first scan may have started before the channel answered. Re-run once with the
        // capability known, so privileged target state (notably a package hidden from this
        // app's visibility view) is not left stale until the user pulls to refresh.
        startScan(true);
        if (dataClean != null) dataClean.refreshAsync();
    }

    /**
     * Full refresh: everything the screen shows is re-derived, not just the probe pipeline.
     *
     * <p>The egress probe is deliberately not part of this path: it opens sockets with
     * multi-second timeouts and has its own button. Play country is queried once per process.
     */
    void rescan() {
        if (playCountry != null && playCountry.startsWith("err")) {
            playCountry = null;      // a failed billing probe is retried on the next manual scan
        }
        ensurePlay();                // UI thread; callback updates the region card only
        startScan(false);
        if (dataClean != null) dataClean.refreshAsync();
    }

    /**
     * One scan path for both the full and the fast reconcile. The generation guard drops a result
     * that completed after a newer scan started; the UI is only rendered once, when the newest
     * result is in hand.
     */
    void startScan(final boolean fast) {
        if (scanning) {
            // The root callback and the opening screen can both ask for a scan. Queue the
            // request instead of running two collectors and leaving the pull gap open.
            scanPendingFast = scanPending ? scanPendingFast && fast : fast;
            scanPending = true;
            return;
        }
        final int generation = SCAN_GENERATION.incrementAndGet();
        scanning = true;
        if (!fast) {
            settle(true);
        }
        new Thread(() -> {
            final long t0 = android.os.SystemClock.elapsedRealtime();
            if (!fast) {
                DeviceSignals.refresh();
                SpaceNames.refresh();
                Targets.refresh(this);
                // Self-injection used to be armed here. Frida in this process made every root
                // interaction noticeably slower and the region transaction does not need it;
                // the network card has its own shell-side probe. Deliberately not armed.
            } else {
                DeviceSignals.refresh();
                if (!Targets.state(Targets.current()).checked) {
                    Targets.refresh(this);
                }
            }
            final com.walter.claudewilson.model.RiskModel[] scannedRisk =
                    new com.walter.claudewilson.model.RiskModel[1];
            final Report report = scanPipeline(fast, scannedRisk);
            Diag.info("perf", (fast ? "fast" : "full") + " scan ms="
                    + (android.os.SystemClock.elapsedRealtime() - t0));
            ScreenUpdates.post(screen -> {
                if (generation != SCAN_GENERATION.get()) {
                    return;
                }
                screen.scanning = false;
                screen.settle(false);
                if (report != null) {
                    screen.pipelineReport = report;
                }
                // The winning scan owns the model. Assigning it from the worker let an older
                // scan finish after a newer billing/environment scan and overwrite its model.
                screen.riskModel = scannedRisk[0];
                screen.requestRender();
                if (screen.scanPending) {
                    boolean nextFast = screen.scanPendingFast;
                    screen.scanPending = false;
                    screen.startScan(nextFast);
                }
            });
        }, "pipeline-scan").start();
    }

    /**
     * Runs the diagnostic pipeline. A failure yields a null report, which the
     * panel states openly, rather than an empty report that would aggregate to a pass.
     */
    private Report scanPipeline(boolean fast,
                                com.walter.claudewilson.model.RiskModel[] riskOut) {
        try {
            final TargetSpec target = Targets.current();
            maybeApplyPendingAutoAlign(target);
            final boolean root = RootShell.rootReady();
            List<Account> accounts;
            boolean readable;
            if (fast && cachedAccountsReadable) {
                accounts = cachedAccounts;
                readable = true;
            } else {
                accounts = shellAccounts();
                readable = accounts != null;
                if (!fast || !cachedAccountsReadable) {
                    cachedAccounts = accounts;
                    cachedAccountsReadable = readable;
                }
            }
            TargetEvidence evidence = targetEvidence(target, root, fast);
            TargetPermissionCollector.CachedFacts targetFacts =
                    new TargetPermissionCollector().collect(this, target, fast);
            Snapshot snapshot = new AndroidSnapshotCollector().collect(
                    this, target.packageName(), accounts, readable, fast,
                    // In the root channel the target runs the persona payload, which hides the
                    // tunnel interfaces/transport/proxy. Mirror that view in the report instead
                    // of injecting frida into this process (the old VpnHide approach, which made
                    // every root interaction slower).
                    root,
                    evidence,
                    targetFacts.permissions,
                    targetFacts.location);
            if (target.id().equals(Targets.current().id())) {
                if ("chatgpt".equals(target.id())) {
                    riskOut[0] = new ChatGptRiskCollector()
                            .collect(this, target, snapshot, playCountry, fast);
                } else if ("claude".equals(target.id())) {
                    riskOut[0] = new ClaudeRiskCollector()
                            .collect(this, target, snapshot, playCountry, fast);
                } else {
                    riskOut[0] = null;
                }
            }
            return ProbeRegistry.scan(snapshot);
        } catch (Exception e) {
            Diag.warn("pipeline", "scan failed", e);
            return null;
        }
    }

    /**
     * A region switch while the target was running cannot edit its DataStore. The pending ISO is
     * written on the first scan that sees the target stopped, before the snapshot reads it.
     */
    private void maybeApplyPendingAutoAlign(TargetSpec target) {
        if (!"chatgpt".equals(target.id()) || !target.localIdentityWrites()) {
            return;
        }
        String pending = applyPrefs().getString("auto_pending_iso", null);
        if (pending == null || !pending.matches("[A-Za-z]{2}")) {
            return;
        }
        if (new RootActions(this).targetRunning(target)) {
            return;
        }
        String result = new TargetDataEdit().setCountryHint(this, target, pending);
        if (result.startsWith("ok")) {
            applyPrefs().edit().remove("auto_pending_iso").apply();
            Audit.record(this, "auto-align", pending + " " + result);
        } else {
            Diag.warn("risk", "pending auto-align failed: " + result, null);
        }
    }

    /**
     * Reuses the target inventory on a fast reconcile. The full scan is the refresh point, and a
     * target switch drops the cache so the new package is never shown with the previous one's
     * stores.
     */
    private TargetEvidence targetEvidence(TargetSpec target, boolean root, boolean fast) {
        int id = RootShell.identity();
        if (fast && cachedEvidence != null && target.id().equals(cachedEvidenceTarget)
                && cachedEvidenceIdentity == id) {
            return cachedEvidence;
        }
        TargetEvidence evidence = new TargetEvidenceCollector().collect(this, target, root);
        cachedEvidence = evidence;
        cachedEvidenceTarget = target.id();
        cachedEvidenceIdentity = id;
        return evidence;
    }

    /**
     * Google accounts from the shell account-service view. Null means unavailable, while an
     * empty list is a successful read that found none.
     */
    private static List<Account> shellAccounts() {
        if (RootShell.state() != RootShell.ST_READY) {
            return null;
        }
        String dump = RootShell.googleAccountsDump();
        return RootShell.failed(dump) ? null : Account.googleFromDumpsys(dump);
    }

    private void render() {
        int scrollY = scroller != null ? scroller.getScrollY() : 0;
        root.removeAllViews();
        header();
        preflightHolder = new LinearLayout(this);
        preflightHolder.setOrientation(LinearLayout.VERTICAL);
        root.addView(preflightHolder);
        personaSection();
        renderPreflightInto(preflightHolder);
        if (network != null) network.render();
        // Both targets render the same item-card surface. The old probe cards described the
        // control plane, not what the target reads.
        riskSection();
        if (dataClean != null) dataClean.render();
        // A full rebuild detached every child; without this the list snapped to the top after
        // every apply/revert, which reads as a jump and made the next tap land on another card.
        if (scroller != null && scrollY > 0) {
            scroller.post(() -> scroller.scrollTo(0, scrollY));
        }
    }

    void refreshPreflight() {
        renderPreflightInto(preflightHolder);
    }

    private void renderPreflightInto(LinearLayout holder) {
        if (holder == null) return;
        holder.removeAllViews();
        TargetSpec target = Targets.current();
        boolean hasPersona = regionIdx >= 0 && regionIdx < Regions.ALL.size();
        Regions.R r = hasPersona ? Regions.ALL.get(regionIdx) : null;
        String regionIso = r != null ? r.iso : "";
        String regionLabel = r != null ? r.dispName() : "";

        NetworkAssessment.Result net = NetworkPanel.lastResult();
        boolean egressProbed = net != null && net.egIp != null && !net.egIp.isEmpty();
        boolean netLeak = net != null && (net.dnsState == 0 || net.v6State == 0
                || net.udpState == 0 || net.routeState == 0
                || net.globalProxySet || net.privateDnsBypass);

        com.walter.claudewilson.model.PreflightVerdict.Input input =
                new com.walter.claudewilson.model.PreflightVerdict.Input(
                        hasPersona,
                        regionIso,
                        personaPanel != null ? personaPanel.mismatches() : 0,
                        personaPanel != null && personaPanel.restrictedNativeSim(),
                        isTargetRunningNow(),
                        egressProbed,
                        net != null ? net.egCc : "",
                        net != null && net.egRestricted,
                        net != null && net.egDatacenter,
                        netLeak,
                        playCountry != null && !playCountry.startsWith("err") ? playCountry : "");
        com.walter.claudewilson.model.PreflightVerdict verdict =
                com.walter.claudewilson.model.PreflightVerdict.evaluate(input);

        new PreflightBar(this, holder, new PreflightBar.Actions() {
            @Override public void stopTarget() {
                forceStopTargetNow(null);
            }

            @Override public void alignPersona() {
                if (regionIdx >= 0) maybeApply();
            }

            @Override public void pickRegion() {
                openRegionPickerSheet();
            }

            @Override public void alignEgress() {
                String cc = NetworkPanel.lastEgressCc();
                if (cc != null && cc.length() == 2) {
                    selectRegionByIso(cc);
                } else {
                    openRegionPickerSheet();
                }
            }

            @Override public void probeNetwork() {
                if (network != null) network.autoProbe();
            }
        }).render(verdict, target.displayName(), regionLabel, applyBusy);
    }

    private boolean isTargetRunningNow() {
        com.walter.claudewilson.model.RiskModel m = riskModel;
        if (m != null) {
            com.walter.claudewilson.model.CheckItem st = m.item("target.state");
            if (st != null && "running".equals(st.value())) {
                return true;
            }
        }
        DataClean.Snapshot clean = dataClean != null ? dataClean.currentSnapshot() : null;
        return clean != null && clean.targetRunning;
    }

    private void forceStopTargetNow(final Runnable after) {
        final TargetSpec target = Targets.current();
        if (!RootShell.shellReady()
                || !com.walter.claudewilson.model.ShellQuote.packageName(target.packageName())) {
            if (after != null) after.run();
            return;
        }
        new Thread(() -> {
            RootShell.run("am force-stop " + target.packageName(), 128, 8000L);
            ScreenUpdates.post(screen -> {
                if (screen.riskModel != null) {
                    screen.riskModel = screen.riskModel.withValue("target.state", "stopped");
                }
                if (after != null) {
                    after.run();
                } else {
                    screen.requestRender();
                    screen.startScan(true);
                    if (screen.dataClean != null) screen.dataClean.refreshAsync();
                }
            });
        }, "cw-stop-target").start();
    }

    /**
     * Immediately harvests Bodyguard exit-info on foreground resume (bypassing the 20s poll
     * throttle) so a target locked in {@code system_server} surfaces its Unlock banner at once.
     */
    void syncBodyguardOnResume() {
        if (!RootShell.rootReady()) {
            return;
        }
        final TargetSpec target = Targets.current();
        final String u = uid();
        final String activeIso = regionApplied ? currentActiveRegionIso() : "";
        new Thread(() -> {
            int ingested = BodyguardExitReader.harvest(activity, target, u, true);
            boolean reconciled = reconcileStaleBodyguardLock(target, u, activeIso);
            if (ingested > 0 || reconciled) {
                ScreenUpdates.post(screen -> {
                    screen.requestRender();
                    if (screen.dataClean != null) screen.dataClean.refreshAsync();
                });
            }
        }, "bg-resume-sync").start();
    }

    /**
     * Reconciles stale Bodyguard locks whose root cause has already been fixed (e.g. a
     * {@code config:missing} lock left behind before the user activated a region, or a country
     * drift lock matching the active region), ensuring the UI Lock Banner and {@code .lock} file
     * never stay out of sync with reality. Historical incident records are read-only audit logs
     * and are never deleted or hidden here.
     */
    private boolean reconcileStaleBodyguardLock(TargetSpec target, String user, String activeIso) {
        if (target == null || !BodyguardStore.isLocked(Dashboard.this, target.id())) {
            return false;
        }
        String isoUp = com.walter.claudewilson.model.IpGuardPolicy.normalizeIso(activeIso);
        if (isoUp.isEmpty()) {
            return false;
        }
        String effectiveCause = BodyguardStore.lockReason(Dashboard.this, target.id());
        if (effectiveCause.isEmpty()) {
            List<LedgerDb.Incident> history =
                    LedgerDb.get(Dashboard.this).loadIncidents(target.id(), user, false);
            for (LedgerDb.Incident inc : history) {
                if ("KILLED".equalsIgnoreCase(inc.actionTaken)
                        || "BLOCKED".equalsIgnoreCase(inc.actionTaken)) {
                    effectiveCause = inc.signalKey;
                    break;
                }
            }
        }
        boolean lockResolved = "config:missing".equals(effectiveCause)
                || "config:corrupt".equals(effectiveCause)
                || effectiveCause.isEmpty()
                || isoUp.equalsIgnoreCase(
                        com.walter.claudewilson.model.IpGuardPolicy
                                .parseTargetIsoFromSignal(effectiveCause));
        if (lockResolved) {
            BodyguardStore.setLocked(Dashboard.this, target.id(), false);
            if (RootShell.rootReady()) {
                new RootActions(Dashboard.this).syncBodyguardLock(target, false);
            }
            return true;
        }
        return false;
    }

    /**
     * Detects an in-place APK update of ClaudeWilson, ensures hook configs are freshly published,
     * and offers a one-tap force-stop for any hooked target still running old in-memory module code.
     */
    private void checkSelfUpgradeAndSync() {
        if (upgradeCheckedThisSession || !RootShell.shellReady()) {
            return;
        }
        upgradeCheckedThisSession = true;
        final String activeBgMode = BodyguardStore.mode(activity, uid());
        final com.walter.claudewilson.model.IpGuardPolicy.Config activeIpCfg =
                BodyguardStore.ipGuardConfig(activity, uid());
        final TargetSpec activeTarget = Targets.current();
        final String activeUser = uid();
        final String activeIso = regionApplied ? currentActiveRegionIso() : "";
        final boolean ipv6Spoof = isIpv6SpoofEnabled();
        new Thread(() -> {
            RootActions ra = new RootActions(activity);
            ra.syncBodyguardGuardMode(activeBgMode);
            if (!ipv6Spoof) {
                ra.syncIpv6Enforcement(false);
            }
            boolean reconciled = reconcileStaleBodyguardLock(activeTarget, activeUser, activeIso);
            if (RootShell.rootReady()) {
                ra.syncIpGuardDaemon(activeTarget, activeBgMode, activeIpCfg, activeIso,
                        com.walter.claudewilson.platform.TablesLoader.load(getApplicationContext()));
            }
            if (reconciled) {
                ScreenUpdates.post(Dashboard::requestRender);
            }
        }, "bg-guard-sync").start();
        String currentStamp;
        try {
            android.content.pm.PackageInfo pi =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            currentStamp = pi.getLongVersionCode() + ":" + pi.lastUpdateTime;
        } catch (Throwable t) {
            return;
        }
        android.content.SharedPreferences meta =
                activity.getSharedPreferences("cw_runtime_meta", 0);
        String prevStamp = meta.getString("last_app_stamp", "");
        meta.edit().putString("last_app_stamp", currentStamp).apply();
        if (!com.walter.claudewilson.model.BodyguardPolicy.isAppStampChanged(
                prevStamp, currentStamp)) {
            suggestDefaultBrowserScopeIfNeeded(meta);
            return;
        }
        suggestDefaultBrowserScopeIfNeeded(meta);
        new Thread(() -> {
            try {
                Thread.sleep(350L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            RootActions actions = new RootActions(activity);
            final List<TargetSpec> stale = new ArrayList<TargetSpec>();
            for (TargetSpec spec : com.walter.claudewilson.model.TargetRegistry.all()) {
                if (spec.hooksEnabled() && actions.targetRunning(spec)) {
                    stale.add(spec);
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < stale.size(); i++) {
                if (i > 0) names.append(" · ");
                names.append(stale.get(i).displayName());
            }
            final String label = names.toString();
            ScreenUpdates.post(screen -> {
                if (screen.activity.isFinishing() || screen.activity.isDestroyed()) {
                    return;
                }
                PersonaEditSheets.showConfirmSheet(
                        screen.activity,
                        Lang.t("模块已更新", "Module updated", "モジュール更新済"),
                        label + Lang.t(" 仍在运行旧版规则",
                                " still running old module hooks",
                                " は旧ルールで稼働中"),
                        Lang.t("强停生效", "Force-stop", "強制停止"),
                        false,
                        () -> new Thread(() -> {
                            for (TargetSpec spec : stale) {
                                if (com.walter.claudewilson.model.ShellQuote
                                        .packageName(spec.packageName())) {
                                    RootShell.run("am force-stop " + spec.packageName(),
                                            128, 8000L);
                                }
                            }
                            ScreenUpdates.post(s -> {
                                Toast.makeText(s.activity,
                                        label + Lang.t(" 已停止", " stopped", " 停止済"),
                                        Toast.LENGTH_SHORT).show();
                                s.startScan(true);
                                if (s.dataClean != null) s.dataClean.refreshAsync();
                            });
                        }, "cw-stop-stale").start());
            });
        }, "cw-upgrade-check").start();
    }

    /**
     * On first run (or if the user changes their default browser), dynamically queries this
     * device's default HTTPS browser and suggests adding it to Vector/LSPosed scope so Chrome
     * Custom Tabs login &amp; Persona KYC inherit full anti-leak protection without hardcoding.
     */
    private void suggestDefaultBrowserScopeIfNeeded(final android.content.SharedPreferences meta) {
        if (!RootShell.rootReady()) {
            return;
        }
        new Thread(() -> {
            final String browserPkg = RootActions.resolveDefaultBrowserPackage(activity);
            if (browserPkg.isEmpty()) {
                return;
            }
            String prompted = meta.getString("scope_browser_prompted", "");
            if (browserPkg.equals(prompted)) {
                return;
            }
            // Only suggest when Vector/LSPosed is actually present on the device.
            String hasFramework = RootShell.run(
                    "if [ -d /data/adb/lspd ] || [ -d /data/adb/modules/zygisk_vector ]; then echo 1; else echo 0; fi",
                    16, 2000L).trim();
            if (!"1".equals(hasFramework)) {
                return;
            }
            meta.edit().putString("scope_browser_prompted", browserPkg).apply();
            String label = browserPkg;
            try {
                android.content.pm.PackageManager pm = activity.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(browserPkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) {
                    label = cs.toString() + " (" + browserPkg + ")";
                }
            } catch (Throwable ignored) {
            }
            final String display = label;
            ScreenUpdates.post(screen -> {
                if (screen.activity.isFinishing() || screen.activity.isDestroyed()) {
                    return;
                }
                PersonaEditSheets.showConfirmSheet(
                        screen.activity,
                        Lang.t("建议加入作用域", "Add Browser to Scope", "スコープ追加を推奨"),
                        Lang.t("检测到默认浏览器 ", "Detected default browser ", "既定ブラウザを検出: ")
                                + display
                                + Lang.t("\n网页登录与 Persona KYC 会通过它打开，请将其勾选进 Vector/LSPosed 作用域以防 IPv6 与环境泄露。",
                                "\nOAuth login and Persona KYC open in it; enable it in Vector/LSPosed scope to prevent IPv6 and environment leaks.",
                                "\nログインや Persona KYC で使用されるため、Vector/LSPosed のスコープに追加してください。"),
                        Lang.t("复制包名", "Copy package", "パッケージ名をコピー"),
                        false,
                        () -> {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) screen.activity
                                            .getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "browser-scope", browserPkg));
                            }
                            Toast.makeText(screen.activity,
                                    Lang.t("已复制: ", "Copied: ") + browserPkg,
                                    Toast.LENGTH_SHORT).show();
                        });
            });
        }, "cw-browser-scope-hint").start();
    }

    /** Redesigned 3-segment Target View: Bodyguard, Persona-Match Ledger, and 6-Dim Diagnostics. */
    private void riskSection() {
        new TargetViewPanel(this, root, Targets.current(), uid(), new TargetViewPanel.Actions() {
            @Override public void editTargetCountryHint(String iso) {
                editTargetCountryHintNow(iso);
            }

            @Override public void editPayloadCountryHint(String iso) {
                if (personaPanel != null) {
                    personaPanel.setCountryHint(iso, () -> setRiskValue(
                            "target.payload.cc_hint", iso));
                }
            }

            @Override public void clearPayloadCountryHint() {
                if (personaPanel != null) {
                    personaPanel.clearCountryHint(Dashboard.this::refreshPayloadHintFromRegion);
                }
            }

            @Override public void restoreTargetCountryHint() {
                restoreTargetCountryHintNow();
            }

            @Override public void resetLocalIdentity() {
                confirmIdentityReset();
            }

            @Override public void applyRegion() {
                chooseRegionForRisk();
            }

            @Override public void onBodyguardChanged() {
                final TargetSpec target = Targets.current();
                final boolean locked = BodyguardStore.isLocked(Dashboard.this, target.id());
                final String mode = BodyguardStore.mode(Dashboard.this, uid());
                final com.walter.claudewilson.model.IpGuardPolicy.Config ipCfg =
                        BodyguardStore.ipGuardConfig(Dashboard.this, uid());
                final String expIso = currentActiveRegionIso();
                if (RootShell.shellReady()) {
                    new Thread(() -> {
                        RootActions ra = new RootActions(Dashboard.this);
                        ra.syncBodyguardGuardMode(mode);
                        if (RootShell.rootReady()) {
                            ra.syncBodyguardLock(target, locked);
                            ra.syncIpGuardDaemon(target, mode, ipCfg, expIso,
                                    com.walter.claudewilson.platform.TablesLoader.load(
                                            getApplicationContext()));
                        }
                    }, "bg-lock-sync").start();
                }
                if (personaPanel != null && RootShell.rootReady() && regionIdx >= 0) {
                    personaPanel.publishCurrent();
                }
            }

            @Override public void scrubTargetResidue() {
                final TargetSpec target = Targets.current();
                new Thread(() -> {
                    new RootActions(Dashboard.this).cleanupLegacyTargetFiles(target);
                    ScreenUpdates.post(screen -> {
                        Toast.makeText(screen,
                                Lang.t("已清理目标旧残留", "Target residue scrubbed"),
                                Toast.LENGTH_SHORT).show();
                        screen.startScan(false);
                    });
                }, "scrub-residue").start();
            }

            @Override public String activeRegionIso() {
                return currentActiveRegionIso();
            }

            @Override public void alignRegionByIso(String iso) {
                if (iso != null && !iso.trim().isEmpty()) {
                    selectRegionByIso(iso.trim());
                }
            }

            @Override public void verifyAndUnlockBodyguard(
                    final Runnable onUnlocked,
                    final java.util.function.Consumer<String> onStillUnsafe) {
                final TargetSpec target = Targets.current();
                final String u = uid();
                final String lockReason = BodyguardStore.lockReason(Dashboard.this, target.id());
                String activeIso = currentActiveRegionIso();
                final String expIso = !activeIso.isEmpty()
                        ? activeIso
                        : com.walter.claudewilson.model.IpGuardPolicy.parseExpectedIsoFromSignal(lockReason);
                final boolean requireProxy = "net:vpn-down".equals(lockReason)
                        || (lockReason != null && lockReason.startsWith("egress:direct"));
                final com.walter.claudewilson.model.IpGuardPolicy.Config ipCfg =
                        BodyguardStore.ipGuardConfig(Dashboard.this, u);
                final String mode = BodyguardStore.mode(Dashboard.this, u);
                new Thread(() -> {
                    com.walter.claudewilson.model.Tables tables =
                            com.walter.claudewilson.platform.TablesLoader.load(
                                    getApplicationContext());
                    RootActions ra = new RootActions(Dashboard.this);
                    RootActions.IpVerifyResult res = ra.verifyIpGuardRecovery(
                            target, expIso, ipCfg, tables, requireProxy);
                    if (res.safe) {
                        String[] pinned = RootActions.readLastTargetIp(target);
                        final String baseIp = !pinned[0].isEmpty()
                                ? pinned[0]
                                : com.walter.claudewilson.model.IpGuardPolicy
                                        .parsePrevIpFromFlipSignal(lockReason);
                        final String baseIso = !pinned[1].isEmpty() ? pinned[1] : expIso;
                        boolean ipChanged = !baseIp.isEmpty()
                                && !res.liveIp.isEmpty()
                                && !baseIp.equals(res.liveIp);
                        if (ipChanged) {
                            ScreenUpdates.post(screen -> IpGuardSheet.showBaselineSwitchConfirm(
                                    screen, baseIp, baseIso, res.liveIp, res.liveIso,
                                    () -> new Thread(() -> {
                                        BodyguardStore.setLocked(Dashboard.this, target.id(), false);
                                        if (RootShell.rootReady()) {
                                            RootActions ra2 = new RootActions(Dashboard.this);
                                            ra2.updateLastTargetIp(target, res.liveIp, res.liveIso);
                                            ra2.syncBodyguardLock(target, false);
                                            ra2.syncIpGuardDaemon(target, mode, ipCfg, expIso, tables);
                                        }
                                        ScreenUpdates.post(s -> {
                                            Toast.makeText(s, Lang.t("已设为新基线并解锁",
                                                    "New baseline saved · Unlocked"),
                                                    Toast.LENGTH_SHORT).show();
                                            if (onUnlocked != null) onUnlocked.run();
                                        });
                                    }, "bg-adopt-baseline").start(),
                                    () -> {
                                        if (onStillUnsafe != null) onStillUnsafe.accept("");
                                    }));
                            return;
                        }
                        BodyguardStore.setLocked(Dashboard.this, target.id(), false);
                        if (RootShell.rootReady()) {
                            if (baseIp.isEmpty() && !res.liveIp.isEmpty() && !res.liveIso.isEmpty()) {
                                ra.updateLastTargetIp(target, res.liveIp, res.liveIso);
                            }
                            ra.syncBodyguardLock(target, false);
                            ra.syncIpGuardDaemon(target, mode, ipCfg, expIso, tables);
                        }
                        ScreenUpdates.post(screen -> {
                            String okMsg = !res.liveIso.isEmpty()
                                    ? Lang.t(IpGuardSheet.regionDisplay(res.liveIso) + " 已恢复，已解锁",
                                            res.liveIso + " verified · Unlocked")
                                    : Lang.t("网络已恢复，已解锁", "Network verified · Unlocked");
                            Toast.makeText(screen, okMsg, Toast.LENGTH_SHORT).show();
                            if (onUnlocked != null) onUnlocked.run();
                        });
                    } else {
                        final String failMsg;
                        if (!res.proxyUp && requireProxy) {
                            failMsg = Lang.t("代理还没连上", "Proxy still off", "プロキシ未接続");
                        } else if (!res.liveIso.isEmpty()) {
                            failMsg = Lang.t("当前还是 " + IpGuardSheet.regionDisplay(res.liveIso),
                                    "Still in " + res.liveIso,
                                    "現在も " + res.liveIso);
                        } else {
                            failMsg = Lang.t("网络仍未就绪", "Network still unsafe", "ネットワーク未復旧");
                        }
                        ScreenUpdates.post(screen -> {
                            if (onStillUnsafe != null) onStillUnsafe.accept(failMsg);
                        });
                    }
                }, "bg-verify-unlock").start();
            }
        }).render(riskModel);
    }

    private String currentActiveRegionIso() {
        return regionIdx >= 0 && regionIdx < Regions.ALL.size()
                ? Regions.ALL.get(regionIdx).iso.toUpperCase(Locale.ROOT)
                : "";
    }

    private void setRiskValue(String id, String value) {
        if (riskModel != null) {
            riskModel = riskModel.withValue(id, value);
        }
        requestRender();
    }

    private void refreshPayloadHintFromRegion() {
        String iso = regionIdx >= 0
                ? Regions.ALL.get(regionIdx).iso.toUpperCase(Locale.ROOT) : null;
        setRiskValue("target.payload.cc_hint", iso);
    }

    private void editTargetCountryHintNow(final String iso) {
        if (applyBusy) {
            Toast.makeText(this, Lang.t("等待当前操作完成", "wait for the current action"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final TargetSpec target = Targets.current();
        new Thread(() -> {
            final String result = new TargetDataEdit().setCountryHint(this, target, iso);
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen, result, Toast.LENGTH_LONG).show();
                if (result.startsWith("ok")) {
                    screen.setRiskValue("target.data.country_hint", iso);
                }
            });
        }, "risk-target-data").start();
    }

    private void restoreTargetCountryHintNow() {
        if (applyBusy) {
            Toast.makeText(this, Lang.t("等待当前操作完成", "wait for the current action"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final TargetSpec target = Targets.current();
        new Thread(() -> {
            final String result = new TargetDataEdit().restoreLast(this, target);
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen, result, Toast.LENGTH_LONG).show();
                if (result.startsWith("ok ")) {
                    String value = result.substring(3).trim();
                    if (value.matches("[A-Za-z]{2}")) {
                        screen.setRiskValue("target.data.country_hint",
                                value.toUpperCase(Locale.ROOT));
                    }
                }
            });
        }, "risk-target-restore").start();
    }

    /**
     * Destructive by design: a real local reset is a package-data clear, not five UUID strings
     * edited out of sync. The dialog is the guard rail.
     */
    private void confirmIdentityReset() {
        final TargetSpec target = Targets.current();
        if (!target.localIdentityWrites()) {
            Toast.makeText(this, Lang.t("此目标未开启本地身份写入",
                    "local identity writes are off"), Toast.LENGTH_LONG).show();
            return;
        }
        if (applyBusy) {
            Toast.makeText(this, Lang.t("等待当前操作完成", "wait for the current action"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PersonaEditSheets.showConfirmSheet(
                this,
                Lang.t("重置本地身份", "Reset Local Identity", "ローカル識別子をリセット"),
                Lang.t("会停止并清除 " + target.displayName()
                                + " 的本地数据（登录、会话、缓存）。",
                        "Stops and clears " + target.displayName()
                                + " local data (login, sessions, cache)."),
                Lang.t("重置", "Reset", "リセット"),
                true,
                () -> new Thread(() -> {
                    String result = new TargetIdentityReset().reset(this, target);
                    ScreenUpdates.post(screen -> {
                        Toast.makeText(screen, result, Toast.LENGTH_LONG).show();
                        Audit.record(screen, "identity-reset", result);
                        if (result.startsWith("ok")) {
                            screen.riskModel = null;
                            screen.startScan(false);
                        }
                    });
                }, "identity-reset").start());
    }

    /** Reuses the dark region picker sheet so a single item edit cannot invent a new path. */
    private void chooseRegionForRisk() {
        openRegionPickerSheet();
    }

    /**
     * Coalesces render requests into one frame. A single region switch used to trigger four to
     * six full rebuilds (drain, rescan start, SpaceNames, scan completion, reassert), each one
     * re-creating every card and forking {@code getprop} for the persona rows.
     */
    void requestRender() {
        if (renderQueued) {
            return;
        }
        renderQueued = true;
        if (activity == null || activity.isDestroyed()) {
            renderQueued = false;
            return;
        }
        android.view.View decor = activity.getWindow().getDecorView();
        if (decor != null) {
            decor.postOnAnimation(renderTick);
        } else {
            renderQueued = false;
            render();
        }
    }

    /** Rebuilds only the persona card; used by apply/revert/radio updates. */
    void refreshPersonaCard() {
        refreshPersona();
    }

    /** Updates the space chip without touching the rest of the screen. */
    void refreshSpaceLabel() {
        refreshPersona();
    }

    /**
     * Rebuild the screen from what is already in hand.
     *
     * <p>Used when a capability appeared or vanished (root started, stopped or changed
     * identity). Re-rendering only the region card left the root card naming the identity the
     * app saw at startup, which is a stale claim rather than a wrong computation.
     */
    void refresh() {
        requestRender();
    }

    private void afterAction() {
        startScan(true);
    }

    private void header() {
        // The gap the pull opens: a real layout height, so the spinner always lands in the space
        // above the content regardless of insets. It is 0 at rest and grows while pulling.
        pullHolder = new LinearLayout(this);
        pullHolder.setOrientation(LinearLayout.HORIZONTAL);
        pullHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        pullHolder.setLayoutParams(new LinearLayout.LayoutParams(-1, pullHeight));
        PullSpinner spinner = new PullSpinner(this);
        spinner.setAlpha(pullHeight > 0 ? 1f : 0f);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
        pullHolder.addView(spinner);
        root.addView(pullHolder);

        // Row 1: Brand Lockup + Live Channel Pill + Circular Privacy Eye
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(this, 4), 0, dp(this, 10));

        View mark = new View(this);
        GradientDrawable markBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ACCENT, SAND});
        markBg.setCornerRadius(dp(this, 3));
        mark.setBackground(markBg);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(this, 5), dp(this, 19));
        mlp.rightMargin = dp(this, 8);
        mark.setLayoutParams(mlp);
        titleRow.addView(mark);

        TextView title = txt("ClaudeWilson", 21, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.025f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(title);

        int ch = RootShell.state();
        String cap;
        int capFg;
        int capFill;
        int capStroke;
        boolean needGrant = false;
        if (ch == RootShell.ST_READY && RootShell.identity() == RootShell.ID_ROOT) {
            cap = "root";
            capFg = GREEN;
            capFill = CARD;
            capStroke = 0x5C34D399;
        } else if (ch == RootShell.ST_READY && RootShell.identity() == RootShell.ID_SHELL) {
            cap = "Shizuku";
            capFg = CYAN;
            capFill = CARD;
            capStroke = 0x5C2EC4B6;
        } else {
            cap = ShizukuShell.state() == ShizukuShell.ST_NO_PERM
                    ? Lang.t("Shizuku 未授权", "Shizuku denied")
                    : Lang.t("未授权", "No root");
            capFg = AMBER;
            capFill = 0x22E5A93B;
            capStroke = 0x73E5A93B;
            needGrant = true;
        }

        LinearLayout chanPill = new LinearLayout(this);
        chanPill.setOrientation(LinearLayout.HORIZONTAL);
        chanPill.setGravity(Gravity.CENTER_VERTICAL);
        chanPill.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(capFill);
        cbg.setCornerRadius(dp(this, 14));
        cbg.setStroke(dp(this, 1), capStroke);
        chanPill.setBackground(cbg);

        TextView capTv = txt(cap, 10, capFg);
        capTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        capTv.setSingleLine(true);
        chanPill.addView(capTv);
        if (needGrant) {
            chanPill.setOnClickListener(v -> RootShell.requestPerm(1));
        }
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(-2, -2);
        cplp.rightMargin = dp(this, 7);
        chanPill.setLayoutParams(cplp);
        titleRow.addView(chanPill);

        PrivacyEye eye = new PrivacyEye(this);
        boolean hidden = Privacy.hidden();
        GradientDrawable eyeBg = new GradientDrawable();
        eyeBg.setShape(GradientDrawable.OVAL);
        eyeBg.setColor(hidden ? 0x1F2EC4B6 : CARD);
        eyeBg.setStroke(dp(this, 1), hidden ? CYAN : STROKE);
        eye.setBackground(eyeBg);
        eye.setOnClickListener(v -> {
            Privacy.toggle(this);
            render();
        });
        titleRow.addView(eye, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        root.addView(titleRow);

        // Row 2: Full-width Segmented Target Switcher
        targetStrip();
        verdictIndex = root.getChildCount();
    }

    /** Restraint-first inline pill shown only when the target updated or emitted unseen keys. */
    private TextView auditSentinelPill(TargetSpec spec, Targets.State state) {
        boolean updated = state.drift == com.walter.claudewilson.model.AuditedBaselines.Drift.UPDATED;
        int unseenCount = state.unseenKeys.size();
        String label;
        if (updated && unseenCount > 0) {
            label = Lang.t("待审计 · +" + unseenCount,
                    "audit · +" + unseenCount,
                    "要監査 · +" + unseenCount);
        } else if (updated) {
            label = Lang.t("待审计", "audit", "要監査");
        } else {
            label = Lang.t("新字段 +" + unseenCount,
                    "+" + unseenCount + " keys",
                    "新キー +" + unseenCount);
        }
        TextView pill = txt(label, 10, AMBER);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B200B);
        bg.setCornerRadius(dp(this, 10));
        bg.setStroke(dp(this, 1), 0x66F59E0B);
        pill.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(this, 8);
        pill.setLayoutParams(lp);
        pill.setOnClickListener(v -> showAuditSentinelDialog(spec, state));
        return pill;
    }

    private void showAuditSentinelDialog(TargetSpec spec, Targets.State state) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 22), dp(this, 20), dp(this, 22), dp(this, 16));

        TextView title = txt(spec.displayName() + " · "
                + Lang.t("基线变更", "Baseline drift", "ベースライン差分"), 16, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        if (state.baseline != null) {
            String baseVer = "v" + state.baseline.versionName()
                    + " (" + state.baseline.versionCode() + ")";
            String currVer = "v" + state.version
                    + (state.versionCode > 0 ? " (" + state.versionCode + ")" : "");
            TextView delta = txt(baseVer + "  →  " + currVer, 12, AMBER);
            delta.setTypeface(Typeface.MONOSPACE);
            delta.setPadding(0, dp(this, 12), 0, 0);
            box.addView(delta);
        }

        if (!state.unseenKeys.isEmpty()) {
            TextView keyHead = txt(Lang.t("新捕获遥测键", "Unseen telemetry keys", "未登録テレメトリキー"),
                    11, DIM);
            keyHead.setPadding(0, dp(this, 12), 0, dp(this, 4));
            box.addView(keyHead);
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(state.unseenKeys.size(), 12);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append('\n');
                sb.append("• ").append(state.unseenKeys.get(i));
            }
            if (state.unseenKeys.size() > limit) {
                sb.append("\n… +").append(state.unseenKeys.size() - limit);
            }
            TextView keys = txt(sb.toString(), 11, FG);
            keys.setTypeface(Typeface.MONOSPACE);
            box.addView(keys);
        }

        final String cmd = "python tools/target_watch/watch.py --target " + spec.id();
        TextView cmdView = txt(cmd, 11, CYAN);
        cmdView.setTypeface(Typeface.MONOSPACE);
        cmdView.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        GradientDrawable cmdBg = new GradientDrawable();
        cmdBg.setColor(CARD2);
        cmdBg.setCornerRadius(dp(this, 10));
        cmdBg.setStroke(dp(this, 1), STROKE);
        cmdView.setBackground(cmdBg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(this, 14);
        cmdView.setLayoutParams(clp);
        box.addView(cmdView);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(this, 16), 0, 0);

        TextView close = txt(Lang.t("关闭", "close", "閉じる"), 11, DIM);
        close.setGravity(Gravity.CENTER);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(CARD2);
        outline.setCornerRadius(dp(this, 22));
        outline.setStroke(dp(this, 1), STROKE);
        close.setBackground(outline);
        close.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams cw = new LinearLayout.LayoutParams(0, -2, 1);
        cw.rightMargin = dp(this, 8);
        close.setLayoutParams(cw);
        close.setOnClickListener(v -> dialog.dismiss());

        TextView copy = txt(Lang.t("复制命令", "copy cmd", "コピー"), 11, CYAN);
        copy.setGravity(Gravity.CENTER);
        copy.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable accent = new GradientDrawable();
        accent.setColor(0xFF0E2938);
        accent.setCornerRadius(dp(this, 22));
        accent.setStroke(dp(this, 1), CYAN);
        copy.setBackground(accent);
        copy.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams aw = new LinearLayout.LayoutParams(0, -2, 1);
        aw.leftMargin = dp(this, 8);
        copy.setLayoutParams(aw);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("target-watch", cmd));
            }
            Toast.makeText(this, Lang.t("已复制", "copied", "コピーしました"),
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        buttons.addView(close);
        buttons.addView(copy);
        box.addView(buttons);

        dialog.setContentView(box);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(this, 20));
            bg.setStroke(dp(this, 1), STROKE);
            window.setBackgroundDrawable(bg);
            window.setDimAmount(0.6f);
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 56),
                    dp(this, 420));
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void headerVerdict() {
        if (riskModel != null) {
            // Target view: report whether the process is running, not a pass/fail verdict.
            com.walter.claudewilson.model.CheckItem state =
                    riskModel.item("target.state");
            String value = state == null ? "" : state.value();
            if ("running".equals(value)) {
                addVerdictBadge(Lang.t("运行中", "running"), GREEN);
            } else if ("stopped".equals(value)) {
                addVerdictBadge(Lang.t("未运行", "stopped"), MUTE);
            } else {
                addVerdictBadge(Lang.t("状态未知", "unknown"), AMBER);
            }
        } else if (pipelineReport != null) {
            // Scan fallback only: while the target model is still being built, the header must
            // not present the previous scan as if it were the new one.
            Status verdict = pipelineReport.verdict();
            int fails = pipelineReport.count(Status.FAIL);
            int warns = pipelineReport.count(Status.WARN);
            int unknown = pipelineReport.count(Status.UNKNOWN);
            String label;
            int color;
            if (verdict == Status.FAIL) {
                label = Lang.t("环境不可信 — " + fails + " 项致命",
                        "Untrusted env — " + fails + " fatal",
                        "環境は信頼不可 — " + fails + " 件致命");
                color = RED;
            } else if (verdict == Status.UNKNOWN) {
                label = Lang.t("信号不完整 — " + unknown + " 项读不到",
                        "Incomplete — " + unknown + " unobserved",
                        "不完全 — " + unknown + " 件未観測");
                color = AMBER;
            } else if (verdict == Status.WARN) {
                label = Lang.t("有风险信号 — " + warns + " 项注意",
                        "Risk signals — " + warns + " warnings",
                        "リスクシグナル — " + warns + " 件注意");
                color = AMBER;
            } else {
                label = Lang.t("可信环境 — 全部通过",
                        "Trusted env — all clear",
                        "信頼できる環境 — すべて合格");
                color = GREEN;
            }
            addVerdictBadge(label, color);
        }
    }

    private void addVerdictBadge(String label, int color) {
        // Alignment state is now shown directly inside the unified Persona workbench card.
    }

    private String regionQuery = "";

    /**
     * Full-width segmented Target Switcher matching Target View's segmented control DNA:
     * displays each target's name, compact version, audit drift pill, and active-region lock.
     */
    private void targetStrip() {
        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        GradientDrawable trayBg = new GradientDrawable();
        trayBg.setColor(CARD);
        trayBg.setCornerRadius(dp(this, 15));
        trayBg.setStroke(dp(this, 1), STROKE);
        tray.setBackground(trayBg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = dp(this, 4);
        tray.setLayoutParams(tlp);

        java.util.List<com.walter.claudewilson.model.TargetSpec> all =
                com.walter.claudewilson.model.TargetRegistry.all();
        for (int i = 0; i < all.size(); i++) {
            final com.walter.claudewilson.model.TargetSpec spec = all.get(i);
            boolean selected = spec == Targets.current();
            boolean locked = !selected && regionIdx >= 0;
            Targets.State st = Targets.state(spec);

            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setGravity(Gravity.CENTER_VERTICAL);
            seg.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
            GradientDrawable segBg = new GradientDrawable();
            segBg.setColor(selected ? CARD2 : 0x00000000);
            segBg.setCornerRadius(dp(this, 11));
            segBg.setStroke(dp(this, 1), selected ? 0x6B2EC4B6 : 0x00000000);
            seg.setBackground(segBg);
            seg.setAlpha(locked ? 0.42f : 1f);
            seg.setTag("target:" + spec.id());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) slp.leftMargin = dp(this, 4);
            seg.setLayoutParams(slp);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.HORIZONTAL);
            left.setGravity(Gravity.CENTER_VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

            TextView name = txt(spec.displayName(), 12, selected ? FG : DIM);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            left.addView(name);

            String ver = st.checked && st.installed && !st.version.isEmpty()
                    ? compactVersion(st.version) : "";
            if (!ver.isEmpty()) {
                TextView vtv = txt("v" + ver, 9, selected ? DIM : MUTE);
                vtv.setTypeface(Typeface.MONOSPACE);
                vtv.setSingleLine(true);
                vtv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
                vlp.leftMargin = dp(this, 5);
                vtv.setLayoutParams(vlp);
                left.addView(vtv);
            }
            seg.addView(left);

            if (locked) {
                TextView lock = txt("🔒", 9.5f, MUTE);
                seg.addView(lock);
            } else if (st.checked && st.needsAudit()) {
                TextView audit = txt(Lang.t("待审计", "audit", "要監査"), 8.5f, AMBER);
                audit.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                audit.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                GradientDrawable abg = new GradientDrawable();
                abg.setColor(0x24E5A93B);
                abg.setCornerRadius(dp(this, 9));
                abg.setStroke(dp(this, 1), 0x73E5A93B);
                audit.setBackground(abg);
                audit.setOnClickListener(v -> showAuditSentinelDialog(spec, st));
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
                alp.leftMargin = dp(this, 4);
                audit.setLayoutParams(alp);
                seg.addView(audit);
            }

            seg.setOnClickListener(v -> switchTarget(spec));
            tray.addView(seg);
        }
        root.addView(tray);
    }

    private static String compactVersion(String v) {
        if (v == null) return "";
        String t = v.trim();
        int dash = t.indexOf('-');
        if (dash > 0) t = t.substring(0, dash);
        return t.length() > 11 ? t.substring(0, 11) : t;
    }

    private void showTargetLockHint() {
        Toast.makeText(this, Lang.t("请先关闭当前地区伪装",
                "Close active region first", "先に地域切替を解除"),
                Toast.LENGTH_SHORT).show();
    }

    private static String targetStateText(Targets.State state) {
        if (!state.checked) {
            return "";
        }
        if (!state.installed) {
            if ("no-channel".equals(state.note)) {
                return Lang.t("未验证 — 需 root 或 Shizuku",
                        "unverified — needs root or Shizuku",
                        "未確認 — root または Shizuku が必要");
            }
            return Lang.t("未安装", "not installed", "未インストール");
        }
        // Version moved to the root line above the tabs; capability/config words are no longer
        // shown. The line remains only for the transient region-switch hint and missing target.
        return "";
    }

    private void switchTarget(com.walter.claudewilson.model.TargetSpec spec) {
        if (spec == Targets.current()) {
            return;
        }
        // A selected region is device-wide. Until it is switched off, the other target must not
        // be opened: switching would let one target's region be mistaken for the other's state.
        if (regionIdx >= 0) {
            showTargetLockHint();
            return;
        }
        if (applyBusy) {
            Toast.makeText(this, Lang.t("等待当前操作完成", "wait for the current action",
                    "現在の操作を待っています"), Toast.LENGTH_SHORT).show();
            return;
        }
        Targets.select(this, spec);
        regionQuery = "";
        pipelineReport = null;
        riskModel = null;
        cachedEvidence = null;
        cachedEvidenceTarget = "";
        cachedEvidenceIdentity = RootShell.ID_UNKNOWN;
        render();
        startScan(false);
        if (dataClean != null) dataClean.refreshAsync();
    }

    /** Persona-layer settings, including the opt-in "rewrite CSC after apply" flag. */
    private android.content.SharedPreferences applyPrefs() {
        return getSharedPreferences("apply", 0);
    }

    /** Runs the opted-in CSC rewrite when the apply left the region tag disagreeing. */
    private void offerCscRewrite() {
        if (personaPanel != null) personaPanel.rewriteCscIfMismatched();
    }

    private void selectRegionByIso(String iso) {
        if (iso == null || iso.isEmpty()) {
            selectRegionIndex(-1);
            return;
        }
        for (int i = 0; i < Regions.ALL.size(); i++) {
            if (Regions.ALL.get(i).iso.equalsIgnoreCase(iso)) {
                selectRegionIndex(i);
                return;
            }
        }
    }

    private void selectRegionIndex(int idx) {
        selectRegionIndex(idx, false);
    }

    private void selectRegionIndex(final int idx, boolean bypassLiveGuard) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (applyBusy || (!bypassLiveGuard && now - lastRegionTap < 500L)) {
            return;
        }
        if (idx < 0 && regionIdx < 0) {
            if (RootShell.state() == RootShell.ST_READY && EnvSession.hasBaseline(this)) {
                pending = 0;
                doRevert();
            }
            return;
        }
        if (!Targets.current().environmentWrites()) {
            Toast.makeText(this, Lang.t("此目标目前只读诊断；切到可注入目标再改环境",
                    "this target is diagnostic-only; switch to a hookable target",
                    "この対象は読取専用です。注入可能な対象で環境を変更してください"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bypassLiveGuard && idx != regionIdx && isTargetRunningNow()) {
            String nextLbl = idx >= 0 && idx < Regions.ALL.size()
                    ? Regions.ALL.get(idx).dispName()
                    : Lang.t("原机态", "Native", "端末のまま");
            PersonaEditSheets.showTargetRunningGuard(
                    this, Targets.current().displayName(), nextLbl,
                    () -> forceStopTargetNow(() -> selectRegionIndex(idx, true)),
                    () -> selectRegionIndex(idx, true));
            return;
        }
        lastRegionTap = now;
        if (idx < 0 && RootShell.state() != RootShell.ST_READY
                && (regionApplied || EnvSession.hasBaseline(this))) {
            RootShell.requestPerm(1);
            Toast.makeText(this, Lang.t("需要 root 或 Shizuku 才能复位环境",
                    "needs root or Shizuku to restore the environment",
                    "環境の復元には root または Shizuku が必要"), Toast.LENGTH_LONG).show();
            return;
        }
        if (idx != regionIdx) {
            clearSimOverrides();
        }
        regionQuery = "";
        regionIdx = idx;
        if (idx >= 0) {
            RegionPersona.Snapshot saved = RegionPersona.of(this, uid(), Regions.ALL.get(idx));
            applyZone = saved.zone;
            applyLocale = saved.locale;
            regionApplied = false;
            persistSelection();
            if (network != null && !network.hasRecentProbe(60000L)) {
                network.autoProbe();
            }
            maybeApply();
            requestRender();
        } else {
            applyAwaitingGrant = false;
            applyZone = null;
            applyLocale = null;
            regionApplied = false;
            persistSelection();
            pending = 0;
            if (RootShell.state() == RootShell.ST_READY) {
                doRevert();
            }
            requestRender();
        }
    }

    /** Bottom-sheet region picker with live search, exit-IP matching, and restricted-region guard. */
    private void openRegionPickerSheet() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 16));

        // Handle bar
        View handle = new View(this);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(0xFF2B454B);
        hbg.setCornerRadius(dp(this, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(this, 12);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt(Lang.t("切换地区", "Region", "地域切替"), 14, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);
        TextView close = txt("✕", 13, DIM);
        close.setPadding(dp(this, 8), dp(this, 2), dp(this, 4), dp(this, 2));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head);

        // Search box
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint(Lang.t("搜索国家 / ISO / 运营商…", "Search country / ISO / carrier…"));
        search.setHintTextColor(MUTE);
        search.setTextColor(FG);
        search.setTextSize(12);
        search.setSingleLine(true);
        search.setPadding(dp(this, 11), dp(this, 8), dp(this, 11), dp(this, 8));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(CARD2);
        sbg.setCornerRadius(dp(this, 11));
        sbg.setStroke(dp(this, 1), STROKE);
        search.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(this, 10);
        slp.bottomMargin = dp(this, 10);
        search.setLayoutParams(slp);
        sheet.addView(search);

        final android.widget.ListView listView = new android.widget.ListView(this);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(null);
        listView.setDividerHeight(dp(this, 6));
        listView.setSelector(android.R.color.transparent);
        int maxListH = (int) (getResources().getDisplayMetrics().heightPixels * 0.56f);
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, maxListH));
        sheet.addView(listView);

        final com.walter.claudewilson.model.Tables tables =
                com.walter.claudewilson.platform.TablesLoader.load(getApplicationContext());
        final String targetId = Targets.current().id();
        final String egressCc = NetworkPanel.lastEgressCc();

        final java.util.List<Integer> baseOrder = new java.util.ArrayList<Integer>(Regions.ALL.size());
        if (regionIdx >= 0) baseOrder.add(regionIdx);
        int egressIdx = -1;
        if (egressCc != null && egressCc.length() == 2) {
            for (int i = 0; i < Regions.ALL.size(); i++) {
                if (i != regionIdx && Regions.ALL.get(i).iso.equalsIgnoreCase(egressCc)) {
                    egressIdx = i;
                    baseOrder.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < Regions.ALL.size(); i++) {
            if (i != regionIdx && i != egressIdx) {
                baseOrder.add(i);
            }
        }
        final java.util.List<Integer> filtered = new java.util.ArrayList<Integer>();
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int pos) { return filtered.get(pos); }
            @Override public long getItemId(int pos) { return filtered.get(pos); }
            @Override public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                int idx = filtered.get(pos);
                if (idx < 0) {
                    return regionSheetRow(dialog, -1, "✕",
                            Lang.t("原机态", "Native", "端末のまま"),
                            Lang.t("恢复系统基线", "Restore device baseline"),
                            regionIdx < 0, false, false, null);
                }
                Regions.R r = Regions.ALL.get(idx);
                String[] net = PersonaPanel.netFor(r.iso);
                String carrier = net != null ? net[2] : "";
                String loc0 = r.locales.length > 0 ? r.locales[0] : "";
                String sub = loc0 + " · " + r.dispTz() + (carrier.isEmpty() ? "" : " · " + carrier);
                boolean banned = com.walter.claudewilson.model.SimPolicy.isRestrictedIso(tables, r.iso, targetId);
                boolean isEgress = egressCc != null && r.iso.equalsIgnoreCase(egressCc);
                return regionSheetRow(dialog, idx, Regions.flagOf(r.iso),
                        r.dispName(), sub, idx == regionIdx, banned, isEgress,
                        r.iso.toUpperCase(Locale.ROOT));
            }
        };
        listView.setAdapter(adapter);

        final Runnable populate = () -> {
            filtered.clear();
            String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || "off".contains(q) || "native".contains(q)
                    || "原机".contains(q) || "关".contains(q)) {
                filtered.add(-1);
            }
            for (int idx : baseOrder) {
                Regions.R r = Regions.ALL.get(idx);
                if (!q.isEmpty()) {
                    String[] net = PersonaPanel.netFor(r.iso);
                    String carrier = net != null ? net[2] : "";
                    if (!r.matches(q) && !carrier.toLowerCase(Locale.ROOT).contains(q)) {
                        continue;
                    }
                }
                filtered.add(idx);
            }
            adapter.notifyDataSetChanged();
        };
        populate.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) { populate.run(); }
        });

        dialog.setContentView(sheet);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadii(new float[]{dp(this, 22), dp(this, 22), dp(this, 22), dp(this, 22), 0, 0, 0, 0});
            bg.setStroke(dp(this, 1), 0xFF2B454B);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.68f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private View regionSheetRow(final android.app.Dialog dialog, final int idx, String flag,
                                String name, String sub, boolean selected,
                                final boolean banned, boolean isEgress, String isoCode) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0x152EC4B6 : CARD2);
        bg.setCornerRadius(dp(this, 12));
        bg.setStroke(dp(this, 1), selected ? CYAN : STROKE);
        row.setBackground(bg);
        if (banned) row.setAlpha(0.60f);
        row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));

        TextView fl = txt(flag, 18, FG);
        fl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(this, 28), -2);
        flp.rightMargin = dp(this, 9);
        fl.setLayoutParams(flp);
        row.addView(fl);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView nm = txt(name, 12, FG);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setSingleLine(true);
        mid.addView(nm);
        TextView sb = txt(sub, 9.5f, DIM);
        sb.setTypeface(Typeface.MONOSPACE);
        sb.setSingleLine(true);
        sb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(sb);
        row.addView(mid);

        if (banned) {
            row.addView(sheetTag(Lang.t("禁区", "Banned"), RED, 0x24E55353, 0x66E55353));
        } else if (isEgress) {
            row.addView(sheetTag(Lang.t("出口匹配", "Exit"), CYAN, 0x1F2EC4B6, 0x592EC4B6));
        } else if (isoCode != null) {
            row.addView(sheetTag(isoCode, DIM, CARD, STROKE));
        }

        row.setOnClickListener(v -> {
            if (banned) {
                Toast.makeText(this, Lang.t("目标禁区地区 · 已拦截",
                        "Restricted target region · blocked"), Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            selectRegionIndex(idx);
        });
        return row;
    }

    private TextView sheetTag(String text, int fg, int fill, int stroke) {
        TextView t = txt(text, 9, fg);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(this, 7), dp(this, 2), dp(this, 7), dp(this, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(this, 10));
        bg.setStroke(dp(this, 1), stroke);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * A real region change drops every country-bound per-space override (SIM, GPS pin/altitude,
     * custom Wi-Fi SSID/BSSID, country hint, CSC) so values from the previous country never leak
     * into the next one. Re-tapping the same region keeps them.
     */
    private void clearSimOverrides() {
        String user = uid();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            return;
        }
        new PersonaState(this).clearRegionBoundOverrides(user);
    }

    /** A manual timezone pick becomes the region's expected value too. */
    void selectionZone(String zone) {
        selectionLocaleAndZone(null, zone, true);
    }

    /** Atomically updates region locale & timezone (and optionally aligns coordinates to zone). */
    void selectionLocaleAndZone(String locale, String zone, boolean syncLocation) {
        if (regionIdx < 0) {
            return;
        }
        Regions.R r = Regions.ALL.get(regionIdx);
        String user = uid();
        if (locale != null && !locale.isEmpty()) {
            applyLocale = locale;
        }
        if (zone != null && !zone.isEmpty()) {
            applyZone = zone;
        }
        RegionPersona.putLocaleAndZone(this, user, r, applyLocale, applyZone, syncLocation);
        if (syncLocation && zone != null && !zone.isEmpty()) {
            new PersonaState(this).clearLocationPoint(user);
        }
        EnvSession.setActive(this, user, r.iso, applyZone, applyLocale, regionApplied);
        if (personaPanel != null) {
            personaPanel.bindSpaceRegion(user, r.iso, applyZone, applyLocale);
        }
    }

    private void persistSelection() {
        String user = uid();
        if (regionIdx < 0) {
            EnvSession.clearActive(this);
            if (personaPanel != null) personaPanel.clearSpaceRegion(user);
            return;
        }
        Regions.R r = Regions.ALL.get(regionIdx);
        EnvSession.setActive(this, user, r.iso, applyZone, applyLocale, regionApplied);
        if (personaPanel != null) personaPanel.bindSpaceRegion(user, r.iso, applyZone, applyLocale);
    }

    /** Explicit active-state write; the apply worker must not read mutable UI fields. */
    private void persistSelectionFor(String user, String iso, String zone, String locale,
                                     boolean applied) {
        if (iso == null) {
            EnvSession.clearActive(this);
            if (personaPanel != null) personaPanel.clearSpaceRegion(user);
        } else {
            EnvSession.setActive(this, user, iso, zone, locale, applied);
            if (personaPanel != null) personaPanel.bindSpaceRegion(user, iso, zone, locale);
        }
    }

    /**
     * Restore the last selected space and its bound region from the previous run. The zone,
     * locale, and custom coordinates are restored instead of re-picked or overwritten.
     */
    private void restoreSelection() {
        if ((selectedUser == null || selectedUser.isEmpty()) && personaPanel != null) {
            String savedUser = personaPanel.lastSelectedUser();
            if (savedUser != null && com.walter.claudewilson.model.ShellQuote.numeric(savedUser)) {
                selectedUser = savedUser;
            }
        }
        String user = uid();
        String iso = personaPanel != null ? personaPanel.spaceRegionIso(user) : null;
        String zone = personaPanel != null ? personaPanel.spaceRegionZone(user) : null;
        String locale = personaPanel != null ? personaPanel.spaceRegionLocale(user) : null;
        if (personaPanel != null) {
            String legacyIso = EnvSession.activeIso(this);
            if (com.walter.claudewilson.model.PersonaSalt.shouldMigrateLegacyRegion(
                    personaPanel.legacyRegionMigrated(),
                    personaPanel.hasAnyBoundRegion(),
                    iso, legacyIso)) {
                iso = legacyIso;
                zone = EnvSession.activeZone(this);
                locale = EnvSession.activeLocale(this);
                personaPanel.bindSpaceRegion(user, iso, zone, locale);
            }
            if (!personaPanel.legacyRegionMigrated()) {
                personaPanel.markLegacyRegionMigrated();
            }
        }
        applyRestoredRegion(user, iso, zone, locale);
    }

    private void applyRestoredRegion(String user, String iso, String zone, String locale) {
        if (iso == null || iso.isEmpty()) {
            regionIdx = -1;
            applyZone = null;
            applyLocale = null;
            regionApplied = false;
            return;
        }
        int idx = -1;
        for (int i = 0; i < Regions.ALL.size(); i++) {
            if (iso.equalsIgnoreCase(Regions.ALL.get(i).iso)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            EnvSession.clearActive(this);
            if (personaPanel != null) personaPanel.clearSpaceRegion(user);
            regionIdx = -1;
            applyZone = null;
            applyLocale = null;
            regionApplied = false;
            return;
        }
        Regions.R r = Regions.ALL.get(idx);
        RegionPersona.Snapshot saved = RegionPersona.of(this, user, r);
        regionIdx = idx;
        boolean zoneDiff = com.walter.claudewilson.model.PersonaSalt
                .shouldUpdateRestoredZone(zone, saved.zone);
        boolean locDiff = locale != null && !locale.isEmpty() && !locale.equals(saved.locale);
        if (zoneDiff || locDiff) {
            // Never reset saved custom coordinates during a state restore (syncLocation = false).
            RegionPersona.putLocaleAndZone(this, user, r,
                    locDiff ? locale : null, zoneDiff ? zone : null, false);
        }
        applyZone = (zone == null || zone.isEmpty()) ? saved.zone : zone;
        applyLocale = (locale == null || locale.isEmpty()) ? saved.locale : locale;
        regionApplied = com.walter.claudewilson.model.PersonaSalt.isRegionAppliedForSpace(
                user, iso,
                EnvSession.activeUser(this),
                EnvSession.activeIso(this),
                EnvSession.activeApplied(this),
                EnvSession.PARTIAL.equals(EnvSession.phase(this)));
    }

    // ---------- root apply layer ----------

    private void maybeApply() {
        int st = RootShell.state();
        if (st == RootShell.ST_READY) {
            applyAwaitingGrant = false;
            doApply();
            return;
        }
        applyAwaitingGrant = true;
        if (st == RootShell.ST_NO_PERM || ShizukuShell.state() == ShizukuShell.ST_NO_PERM) {
            applyLog = ShizukuShell.state() == ShizukuShell.ST_NO_PERM
                    ? Lang.t("在 Shizuku 中允许本应用", "allow this app in Shizuku",
                            "Shizuku で本アプリを許可")
                    : Lang.t("等待 root 或 Shizuku 授权", "awaiting root or Shizuku",
                            "root または Shizuku の許可待ち");
        } else {
            applyLog = Lang.t("请先开启 root 或启动 Shizuku",
                    "enable root or start Shizuku first",
                    "先に root を有効化するか Shizuku を起動");
        }
        RootShell.requestPerm(1);
        requestRender();
    }

    /** Effective carrier for a region: the persisted region salt, else the old global override. */
    private String personaCarrier(PersonaState ps, String user, Regions.R r) {
        RegionPersona.Snapshot saved = RegionPersona.of(this, user, r);
        if (saved != null && saved.carrier != null && !saved.carrier.isEmpty()) {
            return saved.carrier;
        }
        String pc = ps.carrier(user);
        if (pc != null && !pc.isEmpty()) {
            return pc;
        }
        String[] net = PersonaPanel.netFor(r.iso);
        if (net != null) {
            return net[2];
        }
        return r.carriers != null && !r.carriers.isEmpty() ? r.carriers.split("/")[0] : "";
    }

    /** Effective operator code for a region: the persisted region salt, else the old override. */
    private String personaOp(PersonaState ps, String user, Regions.R r) {
        RegionPersona.Snapshot saved = RegionPersona.of(this, user, r);
        if (saved != null && saved.op != null && saved.op.matches("\\d{5,6}")) {
            return saved.op;
        }
        String po = ps.op(user);
        if (po != null && po.matches("\\d{5,6}")) {
            return po;
        }
        String[] net = PersonaPanel.netFor(r.iso);
        return net != null ? net[1] : r.mcc.split("/")[0] + "01";
    }

    /** Desired telephony state for the currently applied region, or null when nothing is applied. */
    private TelephonyReconciler.Desired telephonyDesired() {
        if (!regionApplied || regionIdx < 0) {
            return null;
        }
        Regions.R r = Regions.ALL.get(regionIdx);
        PersonaState ps = new PersonaState(activity);
        String user = uid();
        com.walter.claudewilson.model.SimPolicy.Decision simDec = RegionActions.evaluateRealSim(
                this, Targets.current().id(), ps.keepRealSim(user));
        if (simDec.preserveRealSim) {
            return new TelephonyReconciler.Desired(simDec.resolvedOp, simDec.resolvedIso,
                    simDec.resolvedCarrier, regionActions.activeSub(), true);
        }
        return new TelephonyReconciler.Desired(personaOp(ps, user, r), r.iso,
                personaCarrier(ps, user, r), regionActions.activeSub(), false);
    }

    private static volatile long lastIpv6SyncMs;

    boolean isIpv6SpoofEnabled() {
        return new PersonaState(activity).ipv6SpoofEnabled();
    }

    /**
     * Toggles persistent device-wide IPv6 mode ({@code false} = kernel IPv6 disabled across all
     * interfaces + watchdog + drop-only hooks; {@code true} = kernel IPv6 restored + regional
     * spoofed IPv6 prefix). Syncs kernel, re-publishes persona payload, and repaints UI.
     */
    void setIpv6SpoofEnabled(final boolean enabled) {
        new PersonaState(activity).putIpv6SpoofEnabled(enabled);
        lastIpv6SyncMs = android.os.SystemClock.elapsedRealtime();
        new Thread(() -> new RootActions(activity).syncIpv6Enforcement(enabled),
                "ipv6-policy-sync").start();
        if (personaPanel != null && regionIdx >= 0 && Targets.current().hooksEnabled()) {
            if (enabled) {
                personaPanel.publishRotated();
            } else {
                personaPanel.publishCurrent();
            }
        }
        requestRender();
    }

    /** Re-enforces kernel IPv6 suppression after radio, airplane-mode, or network changes. */
    void ensureIpv6OffEnforced(boolean force) {
        if (!RootShell.rootReady() || isIpv6SpoofEnabled()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && (now - lastIpv6SyncMs) < 1500L) {
            return;
        }
        lastIpv6SyncMs = now;
        new Thread(() -> new RootActions(activity).syncIpv6Enforcement(false),
                "ipv6-guard-enforce").start();
    }

    void networkChanged() {
        ensureIpv6OffEnforced(false);
    }

    void radioChanged() {
        telephony.request(false);
        ensureIpv6OffEnforced(false);
    }

    void radioChanged(boolean force) {
        telephony.request(force);
        ensureIpv6OffEnforced(force);
    }

    private void doApply() {
        if (!Targets.current().environmentWrites()) return;
        if (applyZone == null || applyLocale == null || regionIdx < 0) return;
        if (applyBusy) { pending = 1; return; }
        reverting = false;
        applyBusy = true; applyLog = Lang.t("应用中…", "applying…", "適用中…");
        regionApplied = false;
        requestRender();
        final String z = applyZone, l = applyLocale;
        final int appliedIdx = regionIdx;
        final Regions.R r = Regions.ALL.get(regionIdx);
        final String user = uid();
        final TargetSpec applyTarget = Targets.current();
        final long session = EnvSession.startApply(this, user, r.iso, z, l);
        persistSelection();
        // Everything below is worker work. The old form resolved the subscription, read three
        // properties, resolved the browser package and refreshed the persona card on the UI
        // thread before the shell process even started.
        new Thread(() -> {
            final long t0 = android.os.SystemClock.elapsedRealtime();
            PhoneReloadWatcher.awaitQuiet(3000);
            final long t1 = android.os.SystemClock.elapsedRealtime();
            if (DeviceSignals.getprop("persist.sys.timezone") == null) {
                DeviceSignals.refresh();
            }
            final long t2 = android.os.SystemClock.elapsedRealtime();
            regionActions.saveOriginal(user, applyTarget);
            if (!EnvSession.isCurrent(this, session)) {
                applyBusy = false;
                ScreenUpdates.post(Dashboard::drainPending);
                return;
            }
            final PersonaState ps = new PersonaState(activity);
            final com.walter.claudewilson.model.SimPolicy.Decision simDec =
                    RegionActions.evaluateRealSim(this, applyTarget.id(), ps.keepRealSim(user));
            final boolean preserveSim = simDec.preserveRealSim;
            final String effIso = preserveSim ? simDec.resolvedIso : r.iso;
            final String carrier = preserveSim ? simDec.resolvedCarrier
                    : personaCarrier(ps, user, r);
            final String op = preserveSim ? simDec.resolvedOp : personaOp(ps, user, r);
            final int sub = regionActions.activeSub();
            // The physical card's identity, captured before this apply overwrote anything. Some
            // telephony stacks resolve the APN from these values, so the apply restores them
            // before touching the carrier config.
            final EnvSession.Baseline realBase = EnvSession.baseline(this);
            final String[] realSim = realBase == null ? null
                    : new String[]{realBase.simNum, realBase.simIso, realBase.simAlpha};
            final String browser = RootShell.browserPackage(activity);
            // The device row this space simulates, resolved before the shell starts so the build
            // identity and the injected payload come from the same 14 fields.
            final String[] device = (!ps.deviceHookOnly(user) && personaPanel != null)
                    ? personaPanel.deviceValues(user) : null;
            final String buildSig = device == null ? "" : SystemBuild.signature(device);
            final boolean buildChanged = device != null && !buildSig.isEmpty()
                    && !buildSig.equals(applyPrefs().getString("applied_build_sig", ""));
            final long t3 = android.os.SystemClock.elapsedRealtime();
            final Outcome outcome;
            synchronized (txLock) {
                outcome = RootShell.applyRegion(
                        z, l, effIso, carrier, sub, op, browser,
                        applyTarget.packageName(), device, buildChanged, realSim, user,
                        preserveSim);
            }
            final long t4 = android.os.SystemClock.elapsedRealtime();
            Diag.info("perf", "apply await=" + (t1 - t0) + " props=" + (t2 - t1)
                    + " prep=" + (t3 - t2) + " shell=" + (t4 - t3) + " total=" + (t4 - t0)
                    + " ok=" + outcome.ok());
            if (!EnvSession.isCurrent(this, session)) {
                applyBusy = false;
                ScreenUpdates.post(Dashboard::drainPending);
                return;
            }
            EnvSession.finishApply(this, session, outcome.ok());
            if (outcome.ok()) {
                EnvSession.setSimSpoofed(this, !preserveSim);
            }
            if (outcome.ok() && buildChanged && !outcome.skippedSteps().contains("build-props")) {
                applyPrefs().edit().putString("applied_build_sig", buildSig).apply();
            }
            // Refresh the property cache on this worker before unblocking the UI so the first
            // post-apply render immediately sees the new timezone & SIM props without flashing drift.
            DeviceSignals.refresh();
            applyBusy = false;
            if (user.equals(uid()) && regionIdx == appliedIdx && z.equals(applyZone)) {
                regionApplied = outcome.ok();
                persistSelectionFor(user, r.iso, z, l, outcome.ok());
            }
            final boolean autoWanted = "chatgpt".equals(applyTarget.id()) && RootShell.rootReady();
            final boolean autoStopped = autoWanted
                    && !new RootActions(this).targetRunning(applyTarget);
            final String autoResult;
            if (outcome.ok() && regionApplied && "chatgpt".equals(applyTarget.id())) {
                new PersonaState(activity).clearCountryHint(user);
                if (!RootShell.rootReady()) {
                    autoResult = "";
                } else if (autoStopped) {
                    autoResult = new TargetDataEdit().setCountryHint(this, applyTarget, r.iso);
                } else {
                    applyPrefs().edit().putString("auto_pending_iso", r.iso).apply();
                    autoResult = "pending " + r.iso;
                }
            } else {
                autoResult = "";
            }
            if (autoResult.startsWith("ok")) {
                int at = autoResult.indexOf("backup ");
                if (at >= 0) {
                    applyPrefs().edit().putString("auto_backup",
                            autoResult.substring(at + 7).trim()).apply();
                }
            }
            String autoNote = autoResult.startsWith("ok")
                    ? " · auto " + r.iso
                    : autoResult.startsWith("pending") ? " · auto pending" : "";
            applyLog = "→ " + z + " · " + l + "  " + outcome.summary()
                    + localeSummary(outcome.output())
                    + autoNote
                    + RootShell.suHint(outcome.output());
            Audit.record(this, "apply", z + " " + l + " " + r.iso + " sub=" + sub
                    + " -> " + outcome.summary()
                    + " " + com.walter.claudewilson.model.ShellQuote
                            .suRcTag(outcome.output()));
            if (outcome.ok() && regionApplied) {
                // Telephony commonly rewrites the active slot just after the shell returns. Start
                // a bounded read/write convergence window now that the desired region is visible.
                telephony.request(true);
                final TargetSpec curTarget = Targets.current();
                final String appliedIsoUp = r.iso.toUpperCase(Locale.ROOT);
                reconcileStaleBodyguardLock(curTarget, user, appliedIsoUp);
                if (RootShell.rootReady()) {
                    RootActions ra = new RootActions(Dashboard.this);
                    RootShell.run("rm -f " + com.walter.claudewilson.model.IpGuardPolicy
                            .lastTargetIpPath(curTarget.id()) + " 2>/dev/null || true", 32);
                    ra.syncIpGuardDaemon(
                            curTarget,
                            BodyguardStore.mode(Dashboard.this, user),
                            BodyguardStore.ipGuardConfig(Dashboard.this, user),
                            appliedIsoUp,
                            com.walter.claudewilson.platform.TablesLoader.load(
                                    getApplicationContext()));
                }
                // Publish the rotated payload before triggering drainPending -> fast scan so the
                // fast scan reads the newly written payload JSON instead of racing with it.
                ScreenUpdates.post(screen -> {
                    if (autoWanted && screen.riskModel != null) {
                        if (autoResult.startsWith("ok")) {
                            screen.riskModel = screen.riskModel.withValue(
                                    "target.data.country_hint",
                                    r.iso.toUpperCase(Locale.ROOT));
                        }
                        if (!autoResult.startsWith("failed")) {
                            screen.riskModel = screen.riskModel.withValue(
                                    "target.payload.cc_hint",
                                    r.iso.toUpperCase(Locale.ROOT));
                        }
                    }
                    if (screen.personaPanel != null) {
                        screen.refreshPersona();
                        screen.personaPanel.publishRotated(screen::drainPending);
                    } else {
                        screen.drainPending();
                    }
                });
            } else {
                if (!outcome.ok()) {
                    // Reset failed selection before rolling back so revertNow persists Native state
                    // instead of leaving the UI stuck on the region that failed to apply.
                    if (user.equals(uid()) && regionIdx == appliedIdx) {
                        regionIdx = -1;
                        applyZone = null;
                        applyLocale = null;
                        regionApplied = false;
                        persistSelectionFor(user, null, null, null, false);
                    }
                    boolean reverted = revertNow(user);
                    applyLog += reverted
                            ? Lang.t(" · 失败，已自动回滚", " · failed, rolled back")
                            : Lang.t(" · 失败，回滚未完成，备份保留",
                                    " · failed, rollback incomplete, backup kept");
                }
                ScreenUpdates.post(Dashboard::drainPending);
            }
        }).start();
    }

    /**
     * Turns the {@code LOC <pkg> [tag]} read-back lines into one short clause, so the apply log
     * shows what the system actually stored for T-1, this app and the browser.
     */
    private static String localeSummary(String out) {
        if (out == null) return "";
        StringBuilder b = new StringBuilder();
        for (String line : out.split("\\n")) {
            if (!line.startsWith("LOC ")) continue;
            String[] p = line.substring(4).trim().split(" ", 2);
            if (p.length < 2) continue;
            String tag = p[1].trim().replace("[", "").replace("]", "");
            if (tag.isEmpty()) tag = "—";
            int dot = p[0].lastIndexOf('.');
            b.append(b.length() == 0 ? " · locale " : " / ")
                    .append(dot >= 0 ? p[0].substring(dot + 1) : p[0])
                    .append('=').append(tag);
        }
        return b.toString();
    }

    private void doRevert() {
        if (!Targets.current().environmentWrites()) return;
        if (applyBusy) { pending = 2; return; }
        reverting = true;
        applyBusy = true; applyLog = Lang.t("复位中…", "reverting…", "復元中…");
        requestRender();
        final String userId = uid();
        new Thread(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            revertNow(userId);
            DeviceSignals.refresh();
            Diag.info("perf", "revert ms="
                    + (android.os.SystemClock.elapsedRealtime() - t0));
            reverting = false;
            applyBusy = false;
            ScreenUpdates.post(Dashboard::drainPending);
        }).start();
    }

    /**
     * Runs the revert, records it, and clears the snapshot only when it succeeded.
     *
     * <p>Used both by the button and by the automatic rollback after a failed apply, so the
     * "keep the backup" rule cannot diverge between the two paths.
     */
    private boolean revertNow(String userId) {
        final long session = EnvSession.startRevert(this);
        final EnvSession.Baseline baseline = EnvSession.baseline(this);
        RegionActions.Restore restore = regionActions.prepareRestore(userId);
        long ageMinutes = restore.snapshotAgeMinutes();
        RegionActions.Result result;
        synchronized (txLock) {
            result = restore.execute(restore.appliedUser());
        }
        if (result.needsPhoneReload) {
            // Shell channel cannot rewrite gsm.sim.operator.* directly; reload com.android.phone
            // first so verifyBaseline below inspects the refreshed card state, not stale props.
            PhoneReloadWatcher.runSync();
        }
        if (result.ok && !verifyBaseline(baseline)) {
            result = new RegionActions.Result(
                    result.output + "\nVERIFY:" + verifyBaselineDetail(baseline),
                    result.aidRestored, false, false);
        }
        applyLog = Lang.t("复位 ", "reverted ", "復元 ") + result.output
                + (result.aidRestored ? " aid↩" : "")
                + RootShell.suHint(result.output)
                + (ageMinutes >= 0
                        ? Lang.t(" · 快照 " + ageMinutes + " 分钟前",
                                " · snapshot " + ageMinutes + " min old") : "");
        Audit.record(this, "revert", "u" + userId + " -> " + (result.ok ? "ok" : "failed")
                + (result.aidRestored ? " aid" : "")
                + " " + com.walter.claudewilson.model.ShellQuote.suRcTag(result.output));
        if (result.ok) {
            applyZone = null; applyLocale = null;
            regionApplied = false;
            // The injected payload no longer describes this space, so the rows that only the
            // injection layer can move must stop reporting themselves as ticked.
            new PersonaState(this).clearInjected(userId);
            if (personaPanel != null) personaPanel.clearPublishedConfig();
            final TargetSpec revTarget = Targets.current();
            if (BodyguardStore.isLocked(this, revTarget.id())
                    && com.walter.claudewilson.model.IpGuardPolicy.isIpFlipSignal(
                            BodyguardStore.lockReason(this, revTarget.id()))) {
                BodyguardStore.setLocked(this, revTarget.id(), false);
            }
            if (RootShell.rootReady()) {
                RootActions ra = new RootActions(this);
                ra.syncBodyguardLock(revTarget, BodyguardStore.isLocked(this, revTarget.id()));
                RootShell.run("rm -f " + com.walter.claudewilson.model.IpGuardPolicy
                        .lastTargetIpPath(revTarget.id()) + " 2>/dev/null || true", 32);
                ra.syncIpGuardDaemon(
                        revTarget,
                        com.walter.claudewilson.model.BodyguardPolicy.MODE_OFF,
                        BodyguardStore.ipGuardConfig(this, userId),
                        "",
                        com.walter.claudewilson.platform.TablesLoader.load(
                                getApplicationContext()));
            }
            String backup = applyPrefs().getString("auto_backup", null);
            if (backup != null && RootShell.rootReady()) {
                String restored = new TargetDataEdit().restorePath(this, Targets.current(),
                        backup);
                applyLog += restored.startsWith("ok")
                        ? Lang.t(" · 本地 hint 已恢复", " · local hint restored")
                        : Lang.t(" · 本地 hint 恢复失败", " · local hint restore failed");
                if (restored.startsWith("ok")) {
                    applyPrefs().edit().remove("auto_backup").apply();
                }
            }
            applyPrefs().edit().remove("auto_pending_iso").apply();
            persistSelection();
            restore.clearBackup();
        } else {
            applyLog += Lang.t(" · 备份保留", " · backup kept");
        }
        EnvSession.finishRevert(this, session, result.ok);
        return result.ok;
    }

    private boolean verifyBaseline(EnvSession.Baseline baseline) {
        return verifyBaselineDetail(baseline).isEmpty();
    }

    /** Readback after revert: an output-only success is not acceptance. */
    private String verifyBaselineDetail(EnvSession.Baseline baseline) {
        if (baseline == null) {
            return "";
        }
        DeviceSignals.refresh();
        StringBuilder out = new StringBuilder();
        String tz = DeviceSignals.getprop("persist.sys.timezone");
        if (baseline.tz != null && !baseline.tz.isEmpty()
                && !baseline.tz.equals(tz)) {
            out.append("tz=").append(tz).append(" expected=").append(baseline.tz);
        }
        if (baseline.simNum != null && !baseline.simNum.isEmpty()) {
            String num = DeviceSignals.getprop("gsm.sim.operator.numeric");
            String iso = DeviceSignals.getprop("gsm.sim.operator.iso-country");
            String alpha = DeviceSignals.getprop("gsm.sim.operator.alpha");
            if (!slotHas(baseline.simNum, num)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("sim=").append(num).append(" expected=").append(baseline.simNum);
            }
            if (baseline.simIso != null && !baseline.simIso.isEmpty()
                    && !slotHas(baseline.simIso, iso)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("iso=").append(iso).append(" expected=").append(baseline.simIso);
            }
            if (baseline.simAlpha != null && !baseline.simAlpha.isEmpty()
                    && !slotHas(baseline.simAlpha, alpha)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("alpha=").append(alpha).append(" expected=")
                        .append(baseline.simAlpha);
            }
        }
        return out.toString();
    }

    /** A per-slot property list is one value per SIM slot; the baseline names one card. */
    private static boolean slotHas(String expected, String actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (String part : actual.split(",")) {
            if (expected.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private void drainPending() {
        int p = pending; pending = 0;
        if (p == 1) { doApply(); return; }
        if (p == 2) { doRevert(); return; }
        // An apply or revert just finished (or was attempted). Re-run the probes on a fast
        // snapshot: the expensive, region-independent facts (attestation, TLS, package census)
        // are reused, and the result is rendered once.
        afterAction();
    }

    // ============ 人设空间：Android 多用户 → 新 android_id + 全新安装域 ============
    // pm 包状态操作（install/clear/create-user）不启动目标 app —— 无 launch。
    // install-existing 保留原 installer 记录（vending），免走 Play。

    private TextView personaLog;
    /** True while the pipeline thread is running, so the header can show a spinner. */
    private boolean scanning;
    private LinearLayout pullHolder;
    private int pullHeight;
    private static String personaOut = "";   // static: locale flip recreates
    private static volatile String selectedUser;
    private TextView spaceLabel;

    private void personaSection() {
        section(Lang.t("人设空间", "Persona space", "ペルソナ"));
        if (selectedUser == null || selectedUser.isEmpty()) selectedUser = currentUserId();
        SpaceNames.ensureLoaded();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        card.setBackground(UiSection.card(this));

        personaFields = new LinearLayout(this);
        personaFields.setOrientation(LinearLayout.VERTICAL);
        card.addView(personaFields);
        refreshPersona();

        personaLog = txt(personaOut, 10, DIM);
        personaLog.setTypeface(Typeface.MONOSPACE);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 12);
        card.setLayoutParams(clp);
        root.addView(card);
    }

    private String uid() {
        return selectedUser == null ? currentUserId() : selectedUser;
    }

    static String activeUserId() {
        String u = selectedUser;
        return u == null || u.isEmpty() ? currentUserId() : u;
    }

    /** Bottom-sheet space picker showing real Android users with each space's bound SpaceProfile region. */
    private void spacePicker() {
        Map<String, String> known = SpaceNames.all();
        List<String> ids = new ArrayList<String>(known.keySet());
        String self = currentUserId();
        if (!ids.contains(self)) {
            ids.add(0, self);
        }
        final String current = uid();
        if (!ids.contains(current)) {
            ids.add(current);
        }

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 16));

        View handle = new View(this);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(0xFF2B454B);
        hbg.setCornerRadius(dp(this, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(this, 12);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt(Lang.t("切换空间", "Space", "空間切替"), 14, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);
        TextView close = txt("✕", 13, DIM);
        close.setPadding(dp(this, 8), dp(this, 2), dp(this, 4), dp(this, 2));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head);

        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(this, 10);
        listBox.setLayoutParams(llp);

        for (final String id : ids) {
            boolean sel = id.equals(current);
            String boundIso = personaPanel != null ? personaPanel.spaceRegionIso(id) : null;
            Regions.R reg = null;
            if (boundIso != null) {
                for (Regions.R item : Regions.ALL) {
                    if (item.iso.equalsIgnoreCase(boundIso)) {
                        reg = item;
                        break;
                    }
                }
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(this, 11), dp(this, 10), dp(this, 11), dp(this, 10));
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(sel ? 0x152EC4B6 : CARD2);
            rbg.setCornerRadius(dp(this, 12));
            rbg.setStroke(dp(this, 1), sel ? CYAN : STROKE);
            row.setBackground(rbg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(this, 6);
            row.setLayoutParams(rlp);

            TextView fl = txt(reg != null ? Regions.flagOf(reg.iso) : "✕", 18, reg != null ? FG : DIM);
            fl.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(this, 28), -2);
            flp.rightMargin = dp(this, 9);
            fl.setLayoutParams(flp);
            row.addView(fl);

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            TextView nm = txt(SpaceNames.name(this, id) + "  u" + id, 12, FG);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setSingleLine(true);
            mid.addView(nm);
            String sub = reg != null ? reg.dispName() : Lang.t("原机态", "Native", "端末のまま");
            TextView sb = txt(sub, 9.5f, DIM);
            sb.setTypeface(Typeface.MONOSPACE);
            sb.setSingleLine(true);
            mid.addView(sb);
            row.addView(mid);

            row.addView(sheetTag(
                    reg != null ? reg.iso.toUpperCase(Locale.ROOT) : "OFF",
                    reg != null ? CYAN : DIM,
                    reg != null ? 0x1F2EC4B6 : CARD,
                    reg != null ? 0x592EC4B6 : STROKE));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (!id.equals(uid())) {
                    selectedUser = id;
                    if (personaPanel != null) {
                        personaPanel.putLastSelectedUser(id);
                    }
                    String iso = personaPanel != null ? personaPanel.spaceRegionIso(id) : null;
                    String zone = personaPanel != null ? personaPanel.spaceRegionZone(id) : null;
                    String loc = personaPanel != null ? personaPanel.spaceRegionLocale(id) : null;
                    applyRestoredRegion(id, iso, zone, loc);
                    render();
                    startScan(true);
                    if (dataClean != null) dataClean.refreshAsync();
                }
            });
            listBox.addView(row);
        }
        sheet.addView(listBox);

        dialog.setContentView(sheet);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadii(new float[]{dp(this, 22), dp(this, 22), dp(this, 22), dp(this, 22), 0, 0, 0, 0});
            bg.setStroke(dp(this, 1), 0xFF2B454B);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.68f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static String currentUserId() {
        // Android multiuser uids are userId * 100000 + appId; the public UserHandle
        // helpers are hidden from the SDK stubs, so derive the current user here.
        return String.valueOf(android.os.Process.myUid() / 100000);
    }

    void refreshPersona() {
        if (personaFields != null) personaPanel.renderInto(personaFields);
    }

    /** run a persona op via the root shell; log output. createOp extracts
     *  "created user id N" into the uid field. */
    private void personaOp(String cmd, boolean createOp) {
        if (RootShell.state() != RootShell.ST_READY) {
            personaOut = Lang.t("root 未就绪",
                    "root not ready", "root 未起動");
            personaLog.setText(personaOut);
            return;
        }
        personaOut = "…"; personaLog.setText(personaOut);
        Audit.record(this, "persona", cmd.length() > 60 ? cmd.substring(0, 60) : cmd);
        // Wrapped so a failed step says so itself: `pm` failures print free text, and without a
        // marker the screen showed the error inside an otherwise normal-looking log.
        final String script = com.walter.claudewilson.model.Command.sh()
                .step("persona", cmd).done("PERSONA:DONE").build();
        new Thread(() -> {
            String raw = RootShell.runCapture(script);
            boolean failed = RootShell.failed(raw);
            SpaceNames.observe(raw);
            String out = raw.replace("PERSONA:DONE", "").trim();
            personaOut = failed ? "✗ " + out : out;
            if (failed) {
                Audit.record(this, "persona", "failed "
                        + com.walter.claudewilson.model.ShellQuote.suRcTag(raw));
            }
            ScreenUpdates.post(screen -> {
                screen.personaLog.setText(personaOut);
                if (createOp) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("id (\\d+)").matcher(out);
                    if (m.find()) {
                        selectedUser = m.group(1);
                        screen.requestRender();
                    }
                }
            });
            if (createOp && !failed) {
                // The new clone's ws-* name only appears in a fresh `pm list users`.
                SpaceNames.invalidate();
                SpaceNames.refresh();
            }
        }).start();
    }

    private void ensurePlay() {
        if (playCountry != null || playBusy) return;
        playBusy = true;
        BillingRegion.queryAsync(this, v -> {
            playBusy = false;
            playCountry = v;
            // The Play row is shown even with no region selected, so refresh the persona card
            // regardless of the tab state. A successful country also belongs to the risk model;
            // re-run the fast scan so the account item is not left "unread".
            ScreenUpdates.post(screen -> {
                screen.refreshPersonaCard();
                if (screen.riskModel != null) {
                    String country = v == null || v.startsWith("err")
                            ? null : v.toUpperCase(Locale.ROOT);
                    screen.riskModel = screen.riskModel.withValue(
                            "account.play_country", country);
                }
                screen.requestRender();
                if (v != null && !v.startsWith("err")) {
                    screen.startScan(true);
                }
            });
        });
    }


}
