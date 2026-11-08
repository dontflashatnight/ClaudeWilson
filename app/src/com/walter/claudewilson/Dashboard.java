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
    PersonaPanel personaPanel;
    private LinearLayout personaFields;
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
    private boolean cachedEvidenceRoot;
    private boolean renderQueued;
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
        RootShell.bindSelf(getPackageName());
        ScreenUpdates.attach(this);
        activity.getWindow().setStatusBarColor(BG);
        activity.getWindow().setNavigationBarColor(BG);
        ScrollView sv = new ScrollView(this);
        scroller = sv;
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0A1424, BG, BG}));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 20), dp(this, 28), dp(this, 20), dp(this, 40));
        sv.addView(root);
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
        personaPanel = new PersonaPanel(this, this::render, this::uid,
                () -> playCountry, () -> applyLog, () -> riskModel);
        activity.setContentView(sv);
        // One getprop dump (~40ms) instead of one process per row in every render. Startup only:
        // the switch path refreshes this cache on a worker.
        DeviceSignals.warm();
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
        // One short entrance so the first paint has a beat instead of snapping in.
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        // A locale recreation can occur between worker completion and UI delivery.
        if (!applyBusy && pending != 0) drainPending();
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
                && regionIdx >= 0) {
            // Make the module config match the restored region without re-running the whole
            // apply (which would rewrite timezone/locale as a side effect of opening the app).
            render();
            if (personaPanel != null) personaPanel.publishCurrent();
        } else {
            requestRender();
        }
        // The first scan may have started before the channel answered. Re-run once with the
        // capability known, so privileged target state (notably a package hidden from this
        // app's visibility view) is not left stale until the user pulls to refresh.
        startScan(true);
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
                Targets.refresh(this);
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
            Snapshot snapshot = new AndroidSnapshotCollector().collect(
                    this, target.packageName(), accounts, readable, fast,
                    // In the root channel the target runs the persona payload, which hides the
                    // tunnel interfaces/transport/proxy. Mirror that view in the report instead
                    // of injecting frida into this process (the old VpnHide approach, which made
                    // every root interaction slower).
                    root,
                    evidence);
            if (target.id().equals(Targets.current().id())) {
                if ("chatgpt".equals(target.id())) {
                    riskOut[0] = new ChatGptRiskCollector()
                            .collect(this, target, snapshot, playCountry);
                } else if ("claude".equals(target.id())) {
                    riskOut[0] = new ClaudeRiskCollector()
                            .collect(this, target, snapshot, playCountry);
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
        if (fast && cachedEvidence != null && target.id().equals(cachedEvidenceTarget)
                && cachedEvidenceRoot == root) {
            return cachedEvidence;
        }
        TargetEvidence evidence = new TargetEvidenceCollector().collect(this, target, root);
        cachedEvidence = evidence;
        cachedEvidenceTarget = target.id();
        cachedEvidenceRoot = root;
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
        // 两步：先选空间（人设空间），再选地区（地区切换）。其余卡片都是这两步的结果。
        personaSection();
        headerVerdict();
        regionSection();
        // The network card is the one control-plane surface kept separate on purpose; it sits
        // between the region controls and data cleanup.
        if (network != null) network.render();
        cleanupSection();
        // Both targets render the same item-card surface. The old probe cards described the
        // control plane, not what the target reads.
        riskSection();
        new AccountLayerPanel(this, root).render(cachedAccounts, cachedAccountsReadable);
        // A full rebuild detached every child; without this the list snapped to the top after
        // every apply/revert, which reads as a jump and made the next tap land on another card.
        if (scroller != null && scrollY > 0) {
            scroller.post(() -> scroller.scrollTo(0, scrollY));
        }
    }

    /** One item-level surface for every target; the collector decides what the target observes. */
    private void riskSection() {
        new RiskModelPanel(this, root, new RiskModelPanel.Actions() {
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
        }).render(riskModel);
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
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("重置本地身份", "reset local identity"))
                .setMessage(Lang.t("会停止并清除 " + target.displayName()
                                + " 的本地数据（登录、会话、缓存）。",
                        "This stops and clears " + target.displayName()
                                + " local data (login, conversations, cache)."))
                .setPositiveButton(Lang.t("重置", "reset"), (dialog, which) -> {
                    new Thread(() -> {
                        String result = new TargetIdentityReset().reset(this, target);
                        ScreenUpdates.post(screen -> {
                            Toast.makeText(screen, result, Toast.LENGTH_LONG).show();
                            Audit.record(screen, "identity-reset", result);
                            if (result.startsWith("ok")) {
                                screen.riskModel = null;
                                screen.startScan(false);
                            }
                        });
                    }, "identity-reset").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Reuses the existing region transaction so a single item edit cannot invent a new path. */
    private void chooseRegionForRisk() {
        final java.util.List<Regions.R> rows = Regions.ALL;
        String[] names = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Regions.R r = rows.get(i);
            names[i] = r.name + " · " + r.iso.toUpperCase(Locale.ROOT);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t("地区写入", "region write"))
                .setItems(names, (dialog, index) -> {
                    Regions.R r = rows.get(index);
                    String[] pick = Regions.pick(r);
                    if (index != regionIdx) {
                        clearSimOverrides();
                    }
                    regionIdx = index;
                    applyZone = pick[0];
                    applyLocale = pick[1];
                    regionApplied = false;
                    maybeApply();
                })
                .show();
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
        if (spaceLabel != null) {
            spaceLabel.setText(SpaceNames.name(this, uid()));
        }
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

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt("ClaudeWilson", 30, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(title);
        PrivacyEye eye = new PrivacyEye(this);
        eye.setOnClickListener(v -> {
            Privacy.toggle(this);
            render();
        });
        titleRow.addView(eye, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        root.addView(titleRow);
        // What this machine currently lets the app do. The coloured dot carries the channel;
        // the text only says which one it is.
        int ch = RootShell.state();
        String cap;
        int capColor;
        if (ch == RootShell.ST_READY && RootShell.identity() == RootShell.ID_ROOT) {
            cap = Lang.t("root", "root", "root");
            capColor = GREEN;
        } else if (ch == RootShell.ST_READY && RootShell.identity() == RootShell.ID_SHELL) {
            cap = Lang.t("Shizuku", "Shizuku", "Shizuku");
            capColor = CYAN;
        } else if (ShizukuShell.state() == ShizukuShell.ST_NO_PERM) {
            cap = Lang.t("Shizuku 未授权 — 在 Shizuku 中允许本应用",
                    "Shizuku not granted — allow this app",
                    "Shizuku 未許可 — 本アプリを許可");
            capColor = AMBER;
        } else {
            cap = Lang.t("root / Shizuku 未授权",
                    "root / Shizuku not granted",
                    "root / Shizuku 未許可");
            capColor = AMBER;
        }
        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        capRow.setGravity(Gravity.CENTER_VERTICAL);
        capRow.setPadding(0, dp(this, 8), 0, dp(this, 12));
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(capColor);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp =
                new LinearLayout.LayoutParams(dp(this, 6), dp(this, 6));
        dlp.rightMargin = dp(this, 8);
        dot.setLayoutParams(dlp);
        capRow.addView(dot);
        Targets.State headState = Targets.state(Targets.current());
        String version = headState.checked && headState.installed ? headState.version : "";
        capRow.addView(txt(version.isEmpty() ? cap : cap + " · v" + version, 11, DIM));
        root.addView(capRow);
        targetStrip();
        // The verdict badge is inserted after the persona card has counted its current rows; a
        // number computed before that render was one frame stale and disagreed with the card.
        verdictIndex = root.getChildCount();
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
        TextView badge = txt(label, 15, Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(0, dp(this, 10), 0, dp(this, 10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(this, 12));
        badge.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        // The tabs now sit directly above the badge; keep a little breathing room.
        lp.topMargin = dp(this, 12);
        lp.bottomMargin = dp(this, 10);
        badge.setLayoutParams(lp);
        if (verdictIndex >= 0 && verdictIndex <= root.getChildCount()) {
            root.addView(badge, verdictIndex);
        } else {
            root.addView(badge);
        }
    }

    private String regionQuery = "";

    /**
     * Target selection and one line of state. Targets are peers: switching only changes what is
     * inspected; it never launches the app and never rewrites the system persona.
     */
    private void targetStrip() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        for (com.walter.claudewilson.model.TargetSpec spec
                : com.walter.claudewilson.model.TargetRegistry.all()) {
            boolean selected = spec == Targets.current();
            TextView chip = txt(spec.displayName(), 12, selected ? FG : DIM);
            chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? CARD2 : CARD);
            bg.setCornerRadius(dp(this, 18));
            bg.setStroke(dp(this, selected ? 1 : 1), selected ? CYAN : STROKE);
            chip.setBackground(bg);
            chip.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
            chip.setTag("target:" + spec.id());
            chip.setAlpha(regionIdx >= 0 && !selected ? 0.35f : 1f);
            chip.setOnClickListener(v -> switchTarget(spec));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(this, 8);
            chip.setLayoutParams(lp);
            row.addView(chip);
        }
        root.addView(row);

        com.walter.claudewilson.model.TargetSpec current = Targets.current();
        Targets.State state = Targets.state(current);
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setPadding(0, dp(this, 8), 0, dp(this, 2));
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(!state.checked ? MUTE : state.installed ? GREEN : AMBER);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(this, 6), dp(this, 6));
        dlp.rightMargin = dp(this, 8);
        dot.setLayoutParams(dlp);
        stateRow.addView(dot);
        String normal = targetStateText(state);
        boolean hint = android.os.SystemClock.elapsedRealtime() < targetHintUntil;
        String text = hint ? Lang.t("请关闭地区切换", "close region switch") : normal;
        TextView status = txt(text, 10, hint ? AMBER : MUTE);
        status.setTypeface(Typeface.MONOSPACE);
        targetStatus = status;
        targetStatusRow = stateRow;
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        stateRow.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        stateRow.addView(status);
        root.addView(stateRow);
    }

    /** Shows the one allowed hint in the state line, then restores it without shifting layout. */
    private void showTargetLockHint() {
        targetHintUntil = android.os.SystemClock.elapsedRealtime() + 1800L;
        if (targetStatus != null) {
            if (targetStatusRow != null) {
                targetStatusRow.setVisibility(View.VISIBLE);
            }
            targetStatus.setVisibility(View.VISIBLE);
            targetStatus.setText(Lang.t("请关闭地区切换", "close region switch"));
            targetStatus.setTextColor(AMBER);
            final TextView view = targetStatus;
            view.postDelayed(() -> {
                if (view == targetStatus
                        && android.os.SystemClock.elapsedRealtime() >= targetHintUntil) {
                    String normal = targetStateText(Targets.state(Targets.current()));
                    view.setText(normal);
                    view.setTextColor(MUTE);
                    boolean empty = normal.isEmpty();
                    view.setVisibility(empty ? View.GONE : View.VISIBLE);
                    if (targetStatusRow != null) {
                        targetStatusRow.setVisibility(empty ? View.GONE : View.VISIBLE);
                    }
                }
            }, 1800L);
        }
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
        cachedEvidenceRoot = false;
        render();
        startScan(false);
    }

    /** Persona-layer settings, including the opt-in "rewrite CSC after apply" flag. */
    private android.content.SharedPreferences applyPrefs() {
        return getSharedPreferences("apply", 0);
    }

    /** Runs the opted-in CSC rewrite when the apply left the region tag disagreeing. */
    private void offerCscRewrite() {
        if (personaPanel != null) personaPanel.rewriteCscIfMismatched();
    }

    /**
     * One action, no options: wipe the on-device data the target app and the browser report from.
     * Kept next to the region card because it is the "start over" counterpart of a region switch.
     */
    private void cleanupSection() {
        section(Lang.t("数据清理", "Data cleanup", "データ整理"));
        final boolean readOnly = !Targets.current().localIdentityWrites();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        card.setBackground(UiSection.card(this));
        TextView name = txt(Lang.t("清除数据", "clear data", "データを消去"), 13, FG);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = txt(clearTargetsLabel(), 10, DIM);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, dp(this, 3), 0, 0);
        card.addView(name);
        card.addView(sub);
        if (readOnly) {
            card.setAlpha(0.45f);
            card.setOnClickListener(v -> Toast.makeText(this,
                    Lang.t("此目标目前只读诊断，不做数据写入",
                            "this target is diagnostic-only", "この対象は読取専用"),
                    Toast.LENGTH_SHORT).show());
        } else {
            card.setOnClickListener(v -> confirmClear());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 12);
        card.setLayoutParams(lp);
        root.addView(card);
    }

    /** "Claude · Chrome": what the dialog will clear, in the order it clears them. */
    private String clearTargetsLabel() {
        return labelOf(Targets.current().packageName());
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return l == null || l.length() == 0 ? pkg : l.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * Dark confirmation built from the same primitives as the cards. The platform dialog renders
     * with its own light chrome and a paragraph of text, which is not the surface this app draws.
     */
    private void confirmClear() {
        // Scan the candidate locations first: the dialog then lists what is actually there
        // instead of a fixed, guessed list.
        new Thread(() -> {
            final String pkg = Targets.current().packageName();
            String scan = RootShell.runCapture(DataClean.probe(pkg));
            final java.util.List<String> hits = DataClean.hits(scan);
            if (hits.isEmpty()) hits.add("/data/data/" + pkg);
            ScreenUpdates.post(screen -> screen.showClearDialog(hits));
        }).start();
    }

    /** Dialog over the scanned paths; only what the probe found is listed. */
    private void showClearDialog(java.util.List<String> hits) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 22), dp(this, 20), dp(this, 22), dp(this, 14));

        TextView title = txt(Lang.t("清除数据", "clear data", "データを消去"), 16, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        for (int i = 0; i < hits.size(); i++) {
            TextView name = txt(hits.get(i), 11, FG);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setPadding(0, i == 0 ? dp(this, 14) : dp(this, 10), 0, 0);
            box.addView(name);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(this, 18), 0, 0);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        TextView cancel = txt(Lang.t("取消", "cancel", "キャンセル"), 11, DIM);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(CARD2);
        outline.setCornerRadius(dp(this, 22));
        outline.setStroke(dp(this, 1), STROKE);
        cancel.setBackground(outline);
        cancel.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams cw = new LinearLayout.LayoutParams(0, -2, 1);
        cw.rightMargin = dp(this, 8);
        cancel.setLayoutParams(cw);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView clear = txt(Lang.t("清除", "clear", "消去"), 11, RED);
        clear.setGravity(Gravity.CENTER);
        clear.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable danger = new GradientDrawable();
        danger.setColor(0xFF3A1218);
        danger.setCornerRadius(dp(this, 22));
        danger.setStroke(dp(this, 1), RED);
        clear.setBackground(danger);
        clear.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams dw = new LinearLayout.LayoutParams(0, -2, 1);
        dw.leftMargin = dp(this, 8);
        clear.setLayoutParams(dw);
        clear.setOnClickListener(v -> {
            dialog.dismiss();
            if (RootShell.state() != RootShell.ST_READY) {
                Toast.makeText(this, Lang.t("需要 root 或 Shizuku",
                        "needs root or Shizuku", "root または Shizuku が必要"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            runClear();
        });

        buttons.addView(cancel);
        buttons.addView(clear);
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
            int width = getResources().getDisplayMetrics().widthPixels - dp(this, 56);
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void runClear() {
        final String script = DataClean.command(Targets.current().packageName(), null);
        new Thread(() -> {
            String out = RootShell.runCapture(script);
            boolean ok = DataClean.ok(out);
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen, ok
                                ? Lang.t("已清除", "cleared", "消去しました")
                                : Lang.t("部分未清除，请查看审计", "partly failed, see audit",
                                        "一部失敗（監査を参照）"),
                        Toast.LENGTH_LONG).show();
                Audit.record(screen, "clean", (ok ? "ok " : "failed ") + out);
                screen.rescan();
            });
        }).start();
    }
    private void regionSection() {
        section(Lang.t("地区切换", "Region switch", "地域切替"));
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint(Lang.t("搜索国家/iso/本地名…", "search country / iso / native…",
                "国名・iso・現地名検索…"));
        search.setHintTextColor(0x66DDE4EE);
        search.setTextColor(FG);
        search.setTextSize(12);
        search.setSingleLine(true);
        // The row is filtered by regionQuery, not by the EditText widget. A rebuild without
        // restoring the text produced an empty search box over a still-filtered list.
        search.setText(regionQuery);
        search.setSelection(search.getText().length());
        search.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(CARD); sg.setCornerRadius(dp(this, 8));
        sg.setStroke(dp(this, 1), 0x22FFFFFF);
        search.setBackground(sg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(this, 6);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                regionQuery = e == null ? "" : e.toString();
                buildFlagRow();
            }
        });
        root.addView(search);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        flagRow = new LinearLayout(this);
        flagRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(flagRow);
        root.addView(hs);
        buildFlagRow();
    }

    private void buildFlagRow() {
        flagRow.removeAllViews();
        flagRow.addView(flagItem(-1, "✕", Lang.t("关", "off", "解除")));
        // The selected region stays next to "off" so it is always the first actionable item.
        if (regionIdx >= 0 && Regions.ALL.get(regionIdx).matches(regionQuery)) {
            Regions.R selected = Regions.ALL.get(regionIdx);
            flagRow.addView(flagItem(regionIdx, Regions.flagOf(selected.iso),
                    selected.dispName()));
        }
        for (int i = 0; i < Regions.ALL.size(); i++) {
            if (i == regionIdx) continue;
            Regions.R r = Regions.ALL.get(i);
            if (!r.matches(regionQuery)) continue;
            flagRow.addView(flagItem(i, Regions.flagOf(r.iso), r.dispName()));
        }
    }

    private View flagItem(final int idx, String glyph, String name) {
        boolean sel = idx == regionIdx;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(this, 5), 0, dp(this, 5), 0);
        item.setTag("region:" + idx);

        TextView flag = txt(glyph, 23, sel ? FG : DIM);
        flag.setGravity(Gravity.CENTER);
        GradientDrawable cir = new GradientDrawable();
        cir.setShape(GradientDrawable.OVAL);
        cir.setColor(CARD);
        cir.setStroke(dp(this, sel ? 2 : 1), sel ? CYAN : 0x33FFFFFF);
        flag.setBackground(cir);
        flag.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 52), dp(this, 52)));
        item.addView(flag);

        TextView nm = txt(name, 10, sel ? CYAN : DIM);
        nm.setGravity(Gravity.CENTER_HORIZONTAL);
        nm.setSingleLine(true);
        nm.setPadding(0, dp(this, 3), 0, 0);
        item.addView(nm);

        item.setOnClickListener(v -> {
            // One tap at a time. A second tap while an apply/revert is running used to queue
            // another transaction, which could publish a region the user never waited for.
            long now = android.os.SystemClock.elapsedRealtime();
            if (applyBusy || now - lastRegionTap < 500L) {
                return;
            }
            // Re-tapping "off" while already off is the repair path for a stale snapshot:
            // a previous revert may have reported success before every field was restored.
            if (idx < 0 && regionIdx < 0) {
                if (RootShell.state() == RootShell.ST_READY && EnvSession.hasBaseline(this)) {
                    pending = 0;
                    doRevert();
                }
                return;
            }
            lastRegionTap = now;
            if (!Targets.current().environmentWrites()) {
                Toast.makeText(this, Lang.t("此目标目前只读诊断；切到可注入目标再改环境",
                        "this target is diagnostic-only; switch to a hookable target",
                        "この対象は読取専用です。注入可能な対象で環境を変更してください"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (idx != regionIdx) {
                // Region default wins on a real region change. A same-region re-tap keeps the
                // user's explicit MCC/MNC and carrier edits.
                clearSimOverrides();
            }
            // A chosen region is the end of this search: rebuild the row unfiltered.
            regionQuery = "";
            regionIdx = idx;
            if (idx >= 0) {
                String[] pk = Regions.pick(Regions.ALL.get(idx));
                applyZone = pk[0]; applyLocale = pk[1];
                regionApplied = false;
                persistSelection();
                // One render: it moves the selection ring and the status line. The old path
                // rebuilt only the persona card, so the header stayed one frame behind.
                requestRender();
                maybeApply();
            } else {
                applyZone = null;
                applyLocale = null;
                regionApplied = false;
                persistSelection();
                pending = 0;
                requestRender();
                if (RootShell.state() == RootShell.ST_READY) {
                    doRevert();
                } else {
                    // Without the shell channel nothing can be undone, and silence reads as a bug.
                    Toast.makeText(this, Lang.t("需要 root 或 Shizuku 才能复位环境",
                            "needs root or Shizuku to restore the environment",
                            "環境の復元には root または Shizuku が必要"), Toast.LENGTH_LONG).show();
                }
            }
        });
        return item;
    }

    /**
     * A real region change drops the per-space MCC/MNC and carrier overrides, so the new region's
     * defaults take effect. Re-tapping the same region keeps them.
     */
    private void clearSimOverrides() {
        String user = uid();
        if (!com.walter.claudewilson.model.ShellQuote.numeric(user)) {
            return;
        }
        PersonaState ps = new PersonaState(this);
        ps.clearOp(user);
        ps.clearCarrier(user);
    }

    /** A manual timezone pick becomes the region's expected value too. */
    void selectionZone(String zone) {
        if (regionIdx < 0 || zone == null || zone.isEmpty()) {
            return;
        }
        applyZone = zone;
        EnvSession.setActiveZone(this, zone);
    }

    private void persistSelection() {
        if (regionIdx < 0) {
            EnvSession.clearActive(this);
            return;
        }
        Regions.R r = Regions.ALL.get(regionIdx);
        EnvSession.setActive(this, r.iso, applyZone, applyLocale, regionApplied);
    }

    /** Explicit active-state write; the apply worker must not read mutable UI fields. */
    private void persistSelectionFor(String iso, String zone, String locale, boolean applied) {
        if (iso == null) {
            EnvSession.clearActive(this);
        } else {
            EnvSession.setActive(this, iso, zone, locale, applied);
        }
    }

    /**
     * Restore the tab from the previous run. The zone/locale are restored instead of re-picked,
     * so the expected values do not shuffle between launches; a selection whose region no longer
     * exists in the table is dropped.
     */
    private void restoreSelection() {
        String iso = EnvSession.activeIso(this);
        if (iso == null) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < Regions.ALL.size(); i++) {
            if (iso.equals(Regions.ALL.get(i).iso)) { idx = i; break; }
        }
        if (idx < 0) {
            EnvSession.clearActive(this);
            return;
        }
        String[] fallback = Regions.pick(Regions.ALL.get(idx));
        regionIdx = idx;
        String zone = EnvSession.activeZone(this);
        String locale = EnvSession.activeLocale(this);
        applyZone = zone == null ? fallback[0] : zone;
        applyLocale = locale == null ? fallback[1] : locale;
        regionApplied = EnvSession.activeApplied(this)
                && !EnvSession.PARTIAL.equals(EnvSession.phase(this));
    }

    // ---------- root apply layer ----------

    private void maybeApply() {
        int st = RootShell.state();
        if (st == RootShell.ST_READY) { doApply(); return; }
        if (st == RootShell.ST_NO_PERM) {
            applyLog = ShizukuShell.state() == ShizukuShell.ST_NO_PERM
                    ? Lang.t("在 Shizuku 中允许本应用", "allow this app in Shizuku",
                            "Shizuku で本アプリを許可")
                    : Lang.t("等待 root 或 Shizuku 授权", "awaiting root or Shizuku",
                            "root または Shizuku の許可待ち");
        } else {
            applyLog = Lang.t("KernelSU：手动开启 ClaudeWilson；或启动 Shizuku",
                    "KernelSU: enable ClaudeWilson; or start Shizuku",
                    "KernelSU：ClaudeWilson を有効化、または Shizuku を起動");
        }
        RootShell.requestPerm(1);
        requestRender();
    }

    /** Effective carrier for a region: per-space override, else the curated table, else the row. */
    private static String personaCarrier(PersonaState ps, String user, Regions.R r) {
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

    /** Effective operator code for a region: per-space override, else the curated table. */
    private static String personaOp(PersonaState ps, String user, Regions.R r) {
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
        return new TelephonyReconciler.Desired(personaOp(ps, user, r), r.iso,
                personaCarrier(ps, user, r), regionActions.activeSub());
    }

    void radioChanged() {
        telephony.request(false);
    }

    void radioChanged(boolean force) {
        telephony.request(force);
    }

    private void doApply() {
        if (!Targets.current().environmentWrites()) return;
        if (applyZone == null || applyLocale == null || regionIdx < 0) return;
        if (applyBusy) { pending = 1; return; }
        applyBusy = true; applyLog = Lang.t("应用中…", "applying…", "適用中…");
        regionApplied = false;
        final String z = applyZone, l = applyLocale;
        final int appliedIdx = regionIdx;
        final Regions.R r = Regions.ALL.get(regionIdx);
        final String user = uid();
        final TargetSpec applyTarget = Targets.current();
        final long session = EnvSession.startApply(this, r.iso, z, l);
        persistSelection();
        // Everything below is worker work. The old form resolved the subscription, read three
        // properties, resolved the browser package and refreshed the persona card on the UI
        // thread before the shell process even started.
        new Thread(() -> {
            final long t0 = android.os.SystemClock.elapsedRealtime();
            PhoneReloadWatcher.awaitQuiet(3000);
            final long t1 = android.os.SystemClock.elapsedRealtime();
            DeviceSignals.refresh();
            final long t2 = android.os.SystemClock.elapsedRealtime();
            regionActions.saveOriginal(user, applyTarget);
            if (!EnvSession.isCurrent(this, session)) {
                applyBusy = false;
                ScreenUpdates.post(Dashboard::drainPending);
                return;
            }
            final PersonaState ps = new PersonaState(activity);
            final String carrier = personaCarrier(ps, user, r);
            final String op = personaOp(ps, user, r);
            final int sub = regionActions.activeSub();
            final String browser = RootShell.browserPackage(activity);
            // The device row this space simulates, resolved before the shell starts so the build
            // identity and the injected payload come from the same 14 fields.
            final String[] device = personaPanel != null ? personaPanel.deviceValues(user) : null;
            final String buildSig = device == null ? "" : SystemBuild.signature(device);
            final boolean buildChanged = device != null && !buildSig.isEmpty()
                    && !buildSig.equals(applyPrefs().getString("applied_build_sig", ""));
            final long t3 = android.os.SystemClock.elapsedRealtime();
            final Outcome outcome;
            synchronized (txLock) {
                outcome = RootShell.applyRegion(
                        z, l, r.iso, carrier, sub, op, browser,
                        applyTarget.packageName(), device, buildChanged);
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
            if (outcome.ok() && buildChanged) {
                applyPrefs().edit().putString("applied_build_sig", buildSig).apply();
            }
            applyBusy = false;
            if (regionIdx == appliedIdx && z.equals(applyZone)) {
                regionApplied = outcome.ok();
                persistSelectionFor(r.iso, z, l, outcome.ok());
            }
            final boolean autoWanted = "chatgpt".equals(applyTarget.id());
            final boolean autoStopped = autoWanted
                    && !new RootActions(this).targetRunning(applyTarget);
            final String autoResult;
            if (outcome.ok() && regionApplied && autoWanted) {
                new PersonaState(activity).clearCountryHint(user);
                if (autoStopped) {
                    autoResult = new TargetDataEdit().setCountryHint(this, applyTarget, r.iso);
                } else {
                    applyPrefs().edit().putString("auto_pending_iso", r.iso).apply();
                    autoResult = "pending " + r.iso;
                }
            } else {
                autoResult = "";
            }
            if (outcome.ok() && regionApplied) {
                // The target's language is observed and overridden by the hook; no app-local
                // locale configuration is written here.
            }
            if (autoResult.startsWith("ok")) {
                int at = autoResult.indexOf("backup ");
                if (at >= 0) {
                    applyPrefs().edit().putString("auto_backup",
                            autoResult.substring(at + 7).trim()).apply();
                }
            }
            if (outcome.ok() && regionApplied) {
                // Telephony commonly rewrites the active slot just after the shell returns. Start
                // a bounded read/write convergence window now that the desired region is visible.
                telephony.request(true);
                // The Vector module reads this on the next target process start. Rendering first
                // makes the payload reflect the just-applied region instead of the previous tab.
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
                        screen.render();
                        screen.personaPanel.publishCurrent();
                    }
                    // The item model also contains system fields just written by this apply.
                    screen.startScan(false);
                });
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
            if (!outcome.ok()) {
                // Automatic rollback is the user's choice, but it stays on this worker: the UI
                // is not held on "applying…" while the phone process is restarted again.
                boolean reverted = revertNow(uid());
                applyLog += reverted
                        ? Lang.t(" · 失败，已自动回滚", " · failed, rolled back")
                        : Lang.t(" · 失败，回滚未完成，备份保留",
                                " · failed, rollback incomplete, backup kept");
            }
            ScreenUpdates.post(Dashboard::drainPending);
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
        applyBusy = true; applyLog = Lang.t("复位中…", "reverting…", "復元中…");
        final String userId = uid();
        new Thread(() -> {
            long t0 = android.os.SystemClock.elapsedRealtime();
            revertNow(userId);
            Diag.info("perf", "revert ms="
                    + (android.os.SystemClock.elapsedRealtime() - t0));
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
        if (result.ok && !verifyBaseline(baseline)) {
            result = new RegionActions.Result(
                    result.output + "\nVERIFY:" + verifyBaselineDetail(baseline),
                    result.aidRestored, false, result.needsPhoneReload);
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
            String backup = applyPrefs().getString("auto_backup", null);
            if (backup != null) {
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
        if (result.needsPhoneReload) {
            // The shell channel cannot rewrite gsm.sim.operator.*. Let the phone process restart
            // itself in the background; this call returns immediately.
            PhoneReloadWatcher.start();
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
            if (!baseline.simNum.equals(num)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("sim=").append(num).append(" expected=").append(baseline.simNum);
            }
            if (baseline.simIso != null && !baseline.simIso.isEmpty()
                    && !baseline.simIso.equals(iso)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("iso=").append(iso).append(" expected=").append(baseline.simIso);
            }
            if (baseline.simAlpha != null && !baseline.simAlpha.isEmpty()
                    && !baseline.simAlpha.equals(alpha)) {
                out.append(out.length() > 0 ? "; " : "")
                        .append("alpha=").append(alpha).append(" expected=")
                        .append(baseline.simAlpha);
            }
        }
        return out.toString();
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
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        card.setBackground(UiSection.card(this));

        // 第一步：选空间。用户只需挑一个空间；四个系统操作降级成同一行末尾的次要按钮。
        // 设计里的形态：一行「空间 [id] ▾」。六个操作全在 ▾ 里，界面上不再并排铺开。
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        TextView step1 = chip(Lang.t("空间", "space", "空間"), CYAN);
        if (selectedUser == null || selectedUser.isEmpty()) selectedUser = currentUserId();
        spaceLabel = chip(SpaceNames.name(this, selectedUser), FG);
        spaceLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        spaceLabel.setOnClickListener(v -> spacePicker());
        SpaceNames.ensureLoaded();
        TextView bMenu = chip("▾", CYAN);
        bMenu.setOnClickListener(v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v);
            String[] labels = {
                    Lang.t("＋ 新建空间（克隆资料）", "＋ new space (clone profile)",
                            "＋ 新規（クローン）"),
                    Lang.t("用户列表", "user list", "一覧"),
                    Lang.t("推装到该空间", "push into space", "この空間へ導入"),
                    Lang.t("清空目标数据", "wipe target data", "対象データ消去"),
                    Lang.t("启动该空间", "start space", "この空間を起動"),
                    Lang.t("换一个 AID", "new AID", "AID 変更")};
            for (int i = 0; i < labels.length; i++) {
                // Grey, but deliberately still enabled: a disabled MenuItem never delivers a
                // click, and the requirement is a "coming soon" toast even under root/Shizuku.
                android.text.SpannableString gray =
                        new android.text.SpannableString(labels[i]);
                gray.setSpan(new android.text.style.ForegroundColorSpan(MUTE), 0,
                        gray.length(), 0);
                m.getMenu().add(0, i, i, gray);
            }
            m.setOnMenuItemClickListener(item -> {
                Toast.makeText(this, "coming soon", Toast.LENGTH_SHORT).show();
                return true;
            });
            m.show();
        });
        r1.addView(step1); r1.addView(spaceLabel);
        r1.addView(bMenu);
        card.addView(r1);

        personaFields = new LinearLayout(this);
        personaFields.setOrientation(LinearLayout.VERTICAL);
        card.addView(personaFields);
        refreshPersona();

        personaLog = txt(personaOut, 10, DIM);
        personaLog.setTypeface(Typeface.MONOSPACE);
        personaLog.setPadding(0, dp(this, 6), 0, 0);
        // Space-operation output stays internal; the card shows state, not a log.
        TextView bSim = chip(Lang.t("SIM 管理器", "SIM manager", "SIM マネージャー"), FG);
        bSim.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        bSim.setOnClickListener(v -> new Thread(RootActions::openSimManager).start());
        card.addView(bSim);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 12);
        card.setLayoutParams(clp);
        root.addView(card);
    }

    private String uid() {
        return selectedUser == null ? currentUserId() : selectedUser;
    }

    /**
     * Space selection by name, not by id: the menu lists what `pm list users` reported
     * ("机主", "Secure Folder", ws-* clones), while the numeric id stays an internal detail
     * the shell commands still need.
     */
    private void spacePicker() {
        Map<String, String> known = SpaceNames.all();
        List<String> ids = new ArrayList<String>(known.keySet());
        String self = currentUserId();
        if (!ids.contains(self)) {
            ids.add(0, self);
        }
        String current = uid();
        if (!ids.contains(current)) {
            ids.add(current);
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, spaceLabel);
        for (int i = 0; i < ids.size(); i++) {
            menu.getMenu().add(0, i, i, SpaceNames.name(this, ids.get(i)));
        }
        menu.setOnMenuItemClickListener(item -> {
            selectedUser = ids.get(item.getItemId());
            render();
            return true;
        });
        menu.show();
    }

    private static String currentUserId() {
        // Android multiuser uids are userId * 100000 + appId; the public UserHandle
        // helpers are hidden from the SDK stubs, so derive the current user here.
        return String.valueOf(android.os.Process.myUid() / 100000);
    }

    private void refreshPersona() {
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
