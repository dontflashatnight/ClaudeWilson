package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.model.CheckItem;
import com.walter.claudewilson.model.DiagnosticArchiveParser;
import com.walter.claudewilson.model.DiagnosticClassifier;
import com.walter.claudewilson.model.IpGuardPolicy;
import com.walter.claudewilson.model.PersonaMatchEngine;
import com.walter.claudewilson.model.RiskModel;
import com.walter.claudewilson.model.SimPolicy;
import com.walter.claudewilson.model.Tables;
import com.walter.claudewilson.model.TargetSpec;
import com.walter.claudewilson.platform.TablesLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Redesigned Target View ("目标视角") featuring three focused segments:
 * <ol>
 *   <li><b>保镖 (Bodyguard)</b>: mode toggle (OFF / OBSERVE / BLOCK_KILL), clickable &amp;
 *       searchable Audited Baseline Inspector with reversible user whitelist, clickable 6-Gate
 *       Inspector, and runtime interception feed.</li>
 *   <li><b>报表 (Persona-Match Ledger)</b>: automatic zero-intrusion historical upload matching
 *       matrix with restricted-leak &amp; tainted-identity detection, masked UUID reveal,
 *       expandable per-field timelines, and offline Markdown export.</li>
 *   <li><b>诊断 (6-Dimension Diagnostics)</b>: 2x3 health matrix across Stealth, Integrity,
 *       Network/Location, Identity Chain, Account/Session, and Permissions/Sensors.</li>
 * </ol>
 */
final class TargetViewPanel extends UiSection {

    interface Actions {
        void editTargetCountryHint(String iso);
        void editPayloadCountryHint(String iso);
        void clearPayloadCountryHint();
        void restoreTargetCountryHint();
        void resetLocalIdentity();
        void applyRegion();
        void onBodyguardChanged();
        void scrubTargetResidue();
        String activeRegionIso();
        void alignRegionByIso(String iso);
        void verifyAndUnlockBodyguard(Runnable onUnlocked,
                                      java.util.function.Consumer<String> onStillUnsafe);
    }

    private static final String TAB_BODYGUARD = "bodyguard";
    private static final String TAB_LEDGER = "ledger";
    private static final String TAB_DIAG = "diag";

    private static final String SUB_BASELINE = "baseline";
    private static final String SUB_GATES = "gates";

    // Persist UI state across Dashboard full-tree re-renders.
    private static volatile String activeTab = TAB_BODYGUARD;
    private static volatile String subView = null;
    private static volatile String baselineFilter = "ALL";
    private static volatile String baselineQuery = "";
    private static volatile String expandedSignal = null;
    private static volatile String expandedLedgerRow = null;
    private static volatile String expandedHttpKey = null;
    private static volatile String expandedDiagKey = null;
    private static volatile boolean ledgerMatchedOpen = false;
    private static volatile boolean ledgerUnobservedOpen = false;
    private static volatile boolean ledgerHttpOpen = false;
    private static volatile boolean ledgerDiagOpen = false;
    private static final int MAX_COLLAPSED_INCIDENTS = 3;

    private static volatile String diagDim = DiagnosticClassifier.DIM_STEALTH;
    private static volatile String diagFilter = "ALL";
    private static volatile boolean unlockChecking = false;
    private static volatile boolean incidentsExpanded = false;
    private static volatile String unlockFailHint = "";
    /** True only when a legacy hookcheck file was actually observed inside the target directory. */
    static volatile boolean legacyResidueDetected = false;
    private static final Set<String> REVEALED_IDS = new LinkedHashSet<String>();

    private final Actions actions;
    private final String userId;
    private final TargetSpec target;
    private LinearLayout panelRoot;
    private LinearLayout tabBarHost;
    private LinearLayout contentHost;
    private RiskModel currentModel;

    TargetViewPanel(Context context, LinearLayout root, TargetSpec target,
                    String userId, Actions actions) {
        super(context);
        this.root = root;
        this.target = target;
        this.userId = userId == null || userId.isEmpty() ? "0" : userId;
        this.actions = actions;
    }

    void render(RiskModel model) {
        this.currentModel = model;
        renderHeader();

        // Single unified card container matching PersonaPanel ("人设空间") and NetworkPanel ("网络").
        panelRoot = new LinearLayout(this);
        panelRoot.setOrientation(LinearLayout.VERTICAL);
        panelRoot.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        panelRoot.setBackground(UiSection.card(this));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.bottomMargin = dp(this, 12);
        panelRoot.setLayoutParams(plp);
        root.addView(panelRoot);

        tabBarHost = new LinearLayout(this);
        tabBarHost.setOrientation(LinearLayout.VERTICAL);
        panelRoot.addView(tabBarHost);

        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        panelRoot.addView(contentHost);

        rebuild(false);
    }

    private void renderHeader() {
        section(Lang.t("目标视角", "Target view", "ターゲット"));
    }

    private void rebuild(boolean animate) {
        tabBarHost.removeAllViews();
        contentHost.removeAllViews();

        tabBarHost.setVisibility(View.VISIBLE);
        tabBarHost.addView(buildTabBar());

        if (TAB_LEDGER.equals(activeTab)) {
            renderLedger(contentHost);
        } else if (TAB_DIAG.equals(activeTab)) {
            renderDiagnostics(contentHost);
        } else {
            renderBodyguard(contentHost);
        }

        if (animate) {
            contentHost.setAlpha(0f);
            contentHost.setTranslationY(dp(this, 4));
            contentHost.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(165)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private View buildTabBar() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
        strip.setBackground(roundBox(BG, STROKE, 12));
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(-1, -2);
        stripLp.bottomMargin = dp(this, 12);
        strip.setLayoutParams(stripLp);

        // Live Bodyguard dot color (live lock/mode state only; never polluted by read-only history)
        String mode = BodyguardStore.mode(this, userId);
        boolean locked = BodyguardStore.isLocked(this, target.id());
        int bgDotColor = locked ? RED
                : BodyguardPolicy.MODE_BLOCK_KILL.equals(mode) ? GREEN
                : BodyguardPolicy.MODE_OBSERVE.equals(mode) ? AMBER : MUTE;

        // Live Ledger badge
        PersonaMatchEngine.MatchReport report = LedgerIngestor.buildReport(this, target.id(), userId);
        String ledgerBadge = report.totalEvaluated > 0
                ? report.matchedCount + "/" + report.totalEvaluated : "—";
        int ledgerColor = (report.leakCount > 0 || report.taintedCount > 0) ? RED
                : report.driftCount > 0 ? AMBER
                : report.totalEvaluated > 0 ? GREEN : MUTE;

        // Live Diagnostics badge
        Map<String, DiagnosticClassifier.DimensionGroup> dims =
                DiagnosticClassifier.classify(currentModel);
        int dRisk = 0;
        int dWarn = 0;
        for (DiagnosticClassifier.DimensionGroup g : dims.values()) {
            dRisk += g.riskCount;
            dWarn += g.warnCount;
        }
        String diagBadge = dRisk > 0 ? String.valueOf(dRisk)
                : dWarn > 0 ? String.valueOf(dWarn)
                : Lang.t("6维", "6D", "6軸");
        int diagColor = dRisk > 0 ? RED : dWarn > 0 ? AMBER : GREEN;

        strip.addView(tabCell(TAB_BODYGUARD, Lang.t("保镖", "Guard", "ガード"),
                null, bgDotColor, true));
        strip.addView(tabCell(TAB_LEDGER, Lang.t("报表", "Ledger", "台帳"),
                ledgerBadge, ledgerColor, false));
        strip.addView(tabCell(TAB_DIAG, Lang.t("诊断", "Diag", "診断"),
                diagBadge, diagColor, false));
        return strip;
    }

    private View tabCell(final String tabId, String label, String badge,
                         int accentColor, boolean colorLabel) {
        boolean selected = tabId.equals(activeTab);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(this, 6), dp(this, 7), dp(this, 6), dp(this, 7));
        if (selected) {
            cell.setBackground(roundBox(CARD2, 0xFF2B454B, 9));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        cell.setLayoutParams(lp);

        int labelColor = (colorLabel && accentColor != MUTE)
                ? accentColor
                : (selected ? FG : DIM);
        TextView tv = txt(label, 11, labelColor);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        cell.addView(tv);

        if (badge != null && !badge.isEmpty()) {
            TextView bv = txt(badge, 9.5f, accentColor);
            bv.setTypeface(Typeface.MONOSPACE);
            bv.setSingleLine(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(this, 4);
            bv.setLayoutParams(blp);
            cell.addView(bv);
        }

        bindPress(cell);
        cell.setOnClickListener(v -> {
            if (!tabId.equals(activeTab)) {
                activeTab = tabId;
                subView = null;
                rebuild(true);
            }
        });
        return cell;
    }

    // =========================================================================
    // TAB 1: BODYGUARD (保镖)
    // =========================================================================

    private void renderBodyguard(LinearLayout host) {
        final String mode = BodyguardStore.mode(this, userId);
        final boolean locked = BodyguardStore.isLocked(this, target.id());
        final BodyguardPolicy.TargetBaseline base =
                BodyguardStore.baselineWithCustom(this, target.id());
        final List<BodyguardPolicy.SignalEntry> custom =
                BodyguardStore.customWhitelist(this, target.id(), "");
        final Set<String> whitelistedKeys = new LinkedHashSet<String>();
        for (BodyguardPolicy.SignalEntry ce : custom) {
            whitelistedKeys.add(ce.key);
        }
        // Full read-only incident history (never hidden or deleted when lock state clears)
        final List<LedgerDb.Incident> incidents =
                LedgerDb.get(this).loadIncidents(target.id(), userId, false);

        // 1. Lock Banner (only when locked after a BLOCK_KILL incident)
        if (locked) {
            final String lockReason = BodyguardStore.lockReason(this, target.id());
            final boolean ipLock = IpGuardPolicy.isIpFlipSignal(lockReason);

            LinearLayout banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
            banner.setBackground(roundBox(0x22E55353, RED, 11));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.bottomMargin = dp(this, 10);
            banner.setLayoutParams(blp);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

            TextView msg = txt(formatLockTitle(lockReason, target.displayName()), 11, 0xFFFCA5A5);
            msg.setTypeface(Typeface.DEFAULT_BOLD);
            textCol.addView(msg);

            String subTxt = formatLockSub(lockReason, unlockFailHint);
            if (!subTxt.isEmpty()) {
                TextView sub = txt(subTxt, 9.5f, 0xD9FCA5A5);
                sub.setPadding(0, dp(this, 2), 0, 0);
                textCol.addView(sub);
            }
            banner.addView(textCol);

            if (ipLock && unlockFailHint != null && !unlockFailHint.isEmpty()) {
                TextView forceBtn = pillText(
                        Lang.t("仍要解锁", "Force", "強制解除"),
                        9.5f, 0xFFFCA5A5, 0x00000000, 0x8CE55353);
                bindPress(forceBtn);
                forceBtn.setOnClickListener(v -> {
                    unlockChecking = false;
                    unlockFailHint = "";
                    BodyguardStore.setLocked(this, target.id(), false);
                    Toast.makeText(this, Lang.t("已手动解锁", "Unlocked manually"),
                            Toast.LENGTH_SHORT).show();
                    if (actions != null) actions.onBodyguardChanged();
                    rebuild(false);
                });
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
                flp.leftMargin = dp(this, 6);
                forceBtn.setLayoutParams(flp);
                banner.addView(forceBtn);
            }

            String primaryLbl = ipLock
                    ? (unlockChecking
                        ? Lang.t("检查中…", "Checking…", "確認中…")
                        : Lang.t("检查并解锁", "Check & Unlock", "確認して解除"))
                    : Lang.t("解锁", "Unlock", "解除");
            TextView unlock = primaryChip(primaryLbl, RED, FG);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
            ulp.leftMargin = dp(this, 6);
            unlock.setLayoutParams(ulp);
            unlock.setAlpha(unlockChecking ? 0.65f : 1f);
            unlock.setOnClickListener(v -> {
                if (unlockChecking) {
                    return;
                }
                if (ipLock && actions != null) {
                    unlockChecking = true;
                    unlockFailHint = "";
                    rebuild(false);
                    actions.verifyAndUnlockBodyguard(() -> {
                        unlockChecking = false;
                        unlockFailHint = "";
                        rebuild(false);
                    }, failMsg -> {
                        unlockChecking = false;
                        unlockFailHint = failMsg == null ? "" : failMsg;
                        rebuild(false);
                    });
                    return;
                }
                unlockFailHint = "";
                BodyguardStore.setLocked(this, target.id(), false);
                Toast.makeText(this, Lang.t("已解锁目标启动", "Target unlocked"),
                        Toast.LENGTH_SHORT).show();
                if (actions != null) actions.onBodyguardChanged();
                rebuild(false);
            });
            banner.addView(unlock);
            host.addView(banner);
        } else {
            unlockChecking = false;
            unlockFailHint = "";
        }

        // 2. 3-Column Inner Metric Tiles (CARD2, radius 12dp — matching Persona/Network/DataClean)
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        metrics.setLayoutParams(mlp);

        String customTag = custom.isEmpty() ? "" : "+" + custom.size();
        boolean baseOpen = SUB_BASELINE.equals(subView);
        boolean gatesOpen = SUB_GATES.equals(subView);
        metrics.addView(metricCard(
                String.valueOf(base.entries.size()),
                Lang.t("已审基线", "Baseline", "監査済"),
                FG, customTag, true, baseOpen, 0, dp(this, 6), () -> {
                    if (baseOpen) {
                        subView = null;
                    } else {
                        baselineFilter = "ALL";
                        baselineQuery = "";
                        expandedSignal = null;
                        subView = SUB_BASELINE;
                    }
                    rebuild(false);
                }));
        metrics.addView(metricCard(
                String.valueOf(BodyguardPolicy.ALL_GATES.size()),
                Lang.t("防护项", "Guards", "保護項目"),
                FG, "", true, gatesOpen, 0, dp(this, 6), () -> {
                    subView = gatesOpen ? null : SUB_GATES;
                    rebuild(false);
                }));
        metrics.addView(metricCard(
                String.valueOf(incidents.size()),
                Lang.t("拦截记录", "History", "履歴"),
                locked ? RED : (incidents.isEmpty() ? GREEN : FG), "", subView != null, false,
                0, 0,
                subView != null ? () -> {
                    subView = null;
                    rebuild(false);
                } : null));
        host.addView(metrics);

        // 2b. Standalone IP Protection Card (grouped with metric tiles, clearly separate from
        // the global Bodyguard mode bar at the bottom; hidden while the 8-gate drawer is open
        // where IP Protection already appears at row 1).
        if (!gatesOpen) {
            host.addView(buildIpGuardCard(mode));
        }

        // 3. Inline Drawer (Baseline / Gates) or Read-Only Incident History (grouped by signal+caller)
        if (baseOpen) {
            renderBaselineInspector(host);
        } else if (gatesOpen) {
            renderGatesInspector(host);
        } else if (!incidents.isEmpty()) {
            java.util.Map<String, java.util.List<LedgerDb.Incident>> grouped =
                    new java.util.LinkedHashMap<String, java.util.List<LedgerDb.Incident>>();
            for (LedgerDb.Incident inc : incidents) {
                String gKey = inc.signalKey + "|" + inc.callerFrame + "|" + inc.actionTaken;
                java.util.List<LedgerDb.Incident> bucket = grouped.get(gKey);
                if (bucket == null) {
                    bucket = new ArrayList<LedgerDb.Incident>();
                    grouped.put(gKey, bucket);
                }
                bucket.add(inc);
            }
            final String activeIso = actions != null
                    ? IpGuardPolicy.normalizeIso(actions.activeRegionIso()) : "";
            LinearLayout incWrap = new LinearLayout(this);
            incWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
            ilp.topMargin = dp(this, 8);
            incWrap.setLayoutParams(ilp);
            int totalGroups = grouped.size();
            int limit = incidentsExpanded ? totalGroups : Math.min(MAX_COLLAPSED_INCIDENTS, totalGroups);
            int shown = 0;
            for (java.util.List<LedgerDb.Incident> bucket : grouped.values()) {
                if (shown >= limit) {
                    break;
                }
                incWrap.addView(incidentCard(bucket, activeIso, whitelistedKeys));
                shown++;
            }
            if (totalGroups > MAX_COLLAPSED_INCIDENTS) {
                String toggleTxt = incidentsExpanded
                        ? Lang.t("收起 ▴", "Collapse ▴", "折りたたむ ▴")
                        : Lang.t("展开全部 (" + totalGroups + ") ▾",
                                "Show all (" + totalGroups + ") ▾",
                                "すべて表示 (" + totalGroups + ") ▾");
                TextView toggleBtn = txt(toggleTxt, 10, DIM);
                toggleBtn.setGravity(Gravity.CENTER);
                toggleBtn.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 3));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
                toggleBtn.setLayoutParams(tlp);
                bindPress(toggleBtn);
                toggleBtn.setOnClickListener(v -> {
                    incidentsExpanded = !incidentsExpanded;
                    rebuild(false);
                });
                incWrap.addView(toggleBtn);
            }
            host.addView(incWrap);
        }

        // 4. Dedicated Global Bodyguard Mode Footer (unambiguously scoped to Bodyguard overall)
        host.addView(cardDivider(11, 10));
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        TextView modeLbl = txt(Lang.t("保镖模式", "Guard mode", "ガード設定"), 10.5f, DIM);
        modeLbl.setTypeface(Typeface.DEFAULT_BOLD);
        footer.addView(modeLbl);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        footer.addView(spacer);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(this, 2), dp(this, 2), dp(this, 2), dp(this, 2));
        seg.setBackground(roundBox(BG, STROKE, 10));
        seg.addView(modeSegBtn(BodyguardPolicy.MODE_OFF, Lang.t("关", "Off", "切"), mode));
        seg.addView(modeSegBtn(BodyguardPolicy.MODE_OBSERVE, Lang.t("提醒", "Alert", "通知"), mode));
        seg.addView(modeSegBtn(BodyguardPolicy.MODE_BLOCK_KILL, Lang.t("拦截", "Block", "遮断"), mode));
        footer.addView(seg);
        host.addView(footer);
    }

    private View buildIpGuardCard(String mode) {
        final boolean armed = !BodyguardPolicy.MODE_OFF.equals(mode);
        final String expIso = actions != null
                ? IpGuardPolicy.normalizeIso(actions.activeRegionIso()) : "";
        final boolean ipLocked = BodyguardStore.isLocked(this, target.id())
                && IpGuardPolicy.isIpFlipSignal(BodyguardStore.lockReason(this, target.id()));
        final IpGuardPolicy.Config cfg = BodyguardStore.ipGuardConfig(this, userId);

        String stText;
        int dotColor;
        int stColor;
        int stFill;
        int stStroke;
        if (!armed || !cfg.anyEnabled()) {
            stText = Lang.t("关", "Off", "切");
            dotColor = MUTE; stColor = DIM; stFill = CARD; stStroke = STROKE;
        } else if (ipLocked) {
            stText = Lang.t("已拦住", "Blocked", "遮断");
            dotColor = RED; stColor = 0xFFFCA5A5; stFill = 0x22E55353; stStroke = RED;
        } else if (!RootShell.rootReady() || BodyguardPolicy.MODE_OBSERVE.equals(mode)) {
            stText = (expIso.isEmpty() ? "" : expIso + " · ") + Lang.t("提醒", "Alert", "通知");
            dotColor = AMBER; stColor = AMBER; stFill = 0x1AE5A93B; stStroke = 0x66E5A93B;
        } else if (expIso.isEmpty()) {
            stText = Lang.t("待选地区", "No region", "地域未選択");
            dotColor = DIM; stColor = DIM; stFill = CARD; stStroke = STROKE;
        } else {
            stText = expIso + " · " + Lang.t("保护中", "Active", "保護中");
            dotColor = GREEN; stColor = GREEN; stFill = 0x1634D399; stStroke = 0x5934D399;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        row.setBackground(roundBox(CARD2, STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 6);
        row.setLayoutParams(lp);

        View dot = new View(this);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setShape(GradientDrawable.OVAL);
        dbg.setColor(dotColor);
        dot.setBackground(dbg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(this, 6), dp(this, 6));
        dlp.rightMargin = dp(this, 8);
        dot.setLayoutParams(dlp);
        row.addView(dot);

        TextView title = txt(Lang.t("IP 保护", "IP Protection", "IP 保護"), 11, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(title);

        row.addView(pillText(stText, 9, stColor, stFill, stStroke));

        TextView chev = txt(" ›", 13, SAND);
        row.addView(chev);

        bindPress(row);
        row.setOnClickListener(v -> openIpGuardSheet());
        return row;
    }

    private View modeSegBtn(final String targetMode, String label, String currentMode) {
        boolean on = targetMode.equals(currentMode);
        TextView tv = txt(label, 10, on ? BG : DIM);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(this, 9), dp(this, 5), dp(this, 9), dp(this, 5));
        if (on) {
            int fill = BodyguardPolicy.MODE_BLOCK_KILL.equals(targetMode) ? GREEN
                    : BodyguardPolicy.MODE_OBSERVE.equals(targetMode) ? AMBER : DIM;
            tv.setBackground(roundBox(fill, fill, 8));
        }
        bindPress(tv);
        tv.setOnClickListener(v -> {
            if (!targetMode.equals(currentMode)) {
                BodyguardStore.setMode(this, userId, targetMode);
                if (!BodyguardPolicy.MODE_BLOCK_KILL.equals(targetMode)) {
                    BodyguardStore.setLocked(this, target.id(), false);
                }
                if (actions != null) actions.onBodyguardChanged();
                rebuild(false);
            }
        });
        return tv;
    }

    private View metricCard(String number, String label, int numColor, String cornerTag,
                            boolean clickable, boolean active,
                            int leftMargin, int rightMargin, Runnable onTap) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        tile.setBackground(roundBox(active ? 0xFF19282C : CARD2, active ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
        tile.setLayoutParams(lp);

        TextView lbl = txt(label, 9.5f, DIM);
        lbl.setSingleLine(true);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(lbl);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setGravity(Gravity.BOTTOM);
        bot.setPadding(0, dp(this, 4), 0, 0);
        TextView num = txt(number, 14.5f, numColor);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        num.setSingleLine(true);
        num.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bot.addView(num);
        if (cornerTag != null && !cornerTag.isEmpty()) {
            TextView tag = txt(cornerTag, 9, AMBER);
            tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            bot.addView(tag);
        }
        tile.addView(bot);

        if (clickable && onTap != null) {
            bindPress(tile);
            tile.setOnClickListener(v -> onTap.run());
        }
        return tile;
    }

    private View incidentCard(final java.util.List<LedgerDb.Incident> bucket,
                              final String activeIso,
                              final Set<String> whitelistedKeys) {
        final LedgerDb.Incident inc = bucket.get(0);
        final int count = bucket.size();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        card.setBackground(roundBox(CARD2, STROKE, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 6);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final boolean isIpFlip = BodyguardPolicy.GATE_IP_FLIP.equals(inc.gateType)
                || IpGuardPolicy.isIpFlipSignal(inc.signalKey);
        TextView key = txt(isIpFlip ? formatIpIncidentTitle(inc.signalKey) : inc.signalKey,
                10.5f, FG);
        key.setTypeface(isIpFlip ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE, Typeface.BOLD);
        key.setSingleLine(true);
        key.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        key.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(key);

        boolean killed = "KILLED".equalsIgnoreCase(inc.actionTaken)
                || "BLOCKED".equalsIgnoreCase(inc.actionTaken);
        TextView badge = pillText(
                killed ? Lang.t("已拦住", "Blocked", "遮断") : Lang.t("提醒", "Alert", "通知"),
                9, killed ? RED : AMBER, killed ? 0x22E55353 : 0x22E5A93B,
                killed ? RED : AMBER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(this, 6);
        badge.setLayoutParams(blp);
        top.addView(badge);
        card.addView(top);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setGravity(Gravity.CENTER_VERTICAL);
        bot.setPadding(0, dp(this, 6), 0, 0);
        String meta = formatTime(inc.occurredAt)
                + (isIpFlip ? formatIpIncidentDetail(inc.signalKey)
                        : (inc.callerFrame.isEmpty() ? "" : " · " + inc.callerFrame))
                + (count > 1 ? " ×" + count : "");
        TextView metaTv = txt(meta, 9, MUTE);
        metaTv.setTypeface(isIpFlip ? Typeface.DEFAULT : Typeface.MONOSPACE);
        metaTv.setSingleLine(true);
        metaTv.setEllipsize(TextUtils.TruncateAt.END);
        metaTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bot.addView(metaTv);

        boolean isNoPersona = BodyguardPolicy.GATE_NO_PERSONA.equals(inc.gateType)
                || "config:missing".equals(inc.signalKey)
                || "config:corrupt".equals(inc.signalKey);
        if (isIpFlip) {
            final String driftIso = IpGuardPolicy.parseTargetIsoFromSignal(inc.signalKey);
            Tables tables = TablesLoader.load(getApplicationContext());
            boolean canAlign = !driftIso.isEmpty()
                    && !driftIso.equalsIgnoreCase(activeIso)
                    && !SimPolicy.isRestrictedIso(tables, driftIso, target.id());
            if (canAlign) {
                TextView alignBtn = pillText(
                        Lang.t("地区改为 ", "Switch to ", "地域変更 ") + driftIso,
                        9.5f, CYAN, CARD, CYAN);
                bindPress(alignBtn);
                alignBtn.setOnClickListener(v -> {
                    BodyguardStore.setLocked(this, target.id(), false);
                    if (actions != null) {
                        actions.onBodyguardChanged();
                        actions.alignRegionByIso(driftIso);
                    }
                    rebuild(false);
                });
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
                alp.leftMargin = dp(this, 6);
                alignBtn.setLayoutParams(alp);
                bot.addView(alignBtn);
            }
        } else if (isNoPersona) {
            if (activeIso.isEmpty()) {
                TextView applyBtn = pillText(
                        Lang.t("激活地区", "Apply", "地域適用"),
                        9.5f, CYAN, CARD, CYAN);
                bindPress(applyBtn);
                applyBtn.setOnClickListener(v -> {
                    if (actions != null) actions.applyRegion();
                });
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
                alp.leftMargin = dp(this, 6);
                applyBtn.setLayoutParams(alp);
                bot.addView(applyBtn);
            }
        } else if (whitelistedKeys.contains(inc.signalKey)) {
            TextView allowedTag = txt(Lang.t("已加白", "Allowed", "許可済"), 9.5f, 0xB334D399);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(this, 8);
            allowedTag.setLayoutParams(alp);
            bot.addView(allowedTag);
        } else {
            TextView allowBtn = pillText(
                    Lang.t("加白", "Allow", "許可"),
                    9.5f, FG, CARD, STROKE);
            bindPress(allowBtn);
            allowBtn.setOnClickListener(v -> {
                BodyguardStore.addWhitelist(this, target.id(), inc.gateType, inc.signalKey);
                if (BodyguardStore.isLocked(this, target.id())
                        && inc.signalKey.equals(BodyguardStore.lockReason(this, target.id()))) {
                    BodyguardStore.setLocked(this, target.id(), false);
                }
                if (actions != null) actions.onBodyguardChanged();
                Toast.makeText(this, Lang.t("已加白 · 可在基线中撤销", "Whitelisted"),
                        Toast.LENGTH_SHORT).show();
                rebuild(false);
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(this, 6);
            allowBtn.setLayoutParams(alp);
            bot.addView(allowBtn);
        }

        card.addView(bot);
        return card;
    }

    // =========================================================================
    // DRILL-DOWN 1: BASELINE INSPECTOR (已审基线可点可查)
    // =========================================================================

    private void renderBaselineInspector(LinearLayout host) {
        final BodyguardPolicy.TargetBaseline base =
                BodyguardStore.baselineWithCustom(this, target.id());

        LinearLayout drawer = drawerBox(
                Lang.t("已审基线", "Baseline", "監査済") + " · " + base.entries.size(),
                () -> {
                    subView = null;
                    rebuild(false);
                });

        // Instant Search Box (inset BG inside drawer so EditText keeps focus)
        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.HORIZONTAL);
        searchWrap.setGravity(Gravity.CENTER_VERTICAL);
        searchWrap.setPadding(dp(this, 10), dp(this, 5), dp(this, 10), dp(this, 5));
        searchWrap.setBackground(roundBox(BG, STROKE, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(this, 6);
        slp.bottomMargin = dp(this, 8);
        searchWrap.setLayoutParams(slp);

        TextView icon = txt("⌕", 12, MUTE);
        icon.setPadding(0, 0, dp(this, 6), 0);
        searchWrap.addView(icon);

        EditText search = new EditText(this);
        search.setText(baselineQuery);
        search.setHint(Lang.t("搜索字段 / API / 属性键…", "Search key / API / prop…"));
        search.setHintTextColor(MUTE);
        search.setTextColor(FG);
        search.setTextSize(11);
        search.setTypeface(Typeface.MONOSPACE);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(0, dp(this, 2), 0, dp(this, 2));
        search.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        searchWrap.addView(search);

        drawer.addView(searchWrap);

        // Category Filter Chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 8);
        chipScroll.setLayoutParams(clp);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        int customCnt = BodyguardStore.customWhitelist(this, target.id(), "").size();
        String[][] filters = {
                {"ALL", Lang.t("全部", "All", "全て")},
                {"CUSTOM", Lang.t("加白", "Custom", "許可") + (customCnt > 0 ? " " + customCnt : "")},
                {BodyguardPolicy.GATE_TELEMETRY_KEY, Lang.t("遥测键", "Telemetry", "計測")},
                {BodyguardPolicy.GATE_SYS_PROP, Lang.t("属性", "Props", "属性")},
                {BodyguardPolicy.GATE_SETTINGS_KEY, "Settings"},
                {BodyguardPolicy.GATE_TRIPWIRE_API, "API"},
                {BodyguardPolicy.GATE_DYNAMIC_CODE, Lang.t("动态代码", "DynCode", "動的")},
                {BodyguardPolicy.GATE_SENSITIVE_PATH, Lang.t("路径", "Paths", "経路")},
                {BodyguardPolicy.GATE_EGRESS_HOST, Lang.t("外发域名", "Hosts", "通信先")},
        };
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);

        for (final String[] f : filters) {
            boolean on = f[0].equals(baselineFilter);
            TextView chip = pillText(f[1], 9.5f, on ? BG : DIM, on ? CYAN : CARD2,
                    on ? CYAN : STROKE);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            flp.rightMargin = dp(this, 6);
            chip.setLayoutParams(flp);
            bindPress(chip);
            chip.setOnClickListener(v -> {
                baselineFilter = f[0];
                rebuild(false);
            });
            chipRow.addView(chip);
        }
        chipScroll.addView(chipRow);
        drawer.addView(chipScroll);

        drawer.addView(listBox);
        populateBaselineList(listBox, base.entries);
        host.addView(drawer);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                baselineQuery = s == null ? "" : s.toString();
                populateBaselineList(listBox, base.entries);
            }
        });
    }

    private void populateBaselineList(LinearLayout listBox,
                                      List<BodyguardPolicy.SignalEntry> allEntries) {
        listBox.removeAllViews();
        List<BodyguardPolicy.SignalEntry> filtered =
                BodyguardPolicy.filter(allEntries, baselineFilter, baselineQuery);
        if (filtered.isEmpty()) {
            TextView tv = txt(Lang.t("无匹配项", "No matches", "該当なし"), 10, MUTE);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(this, 14), 0, dp(this, 14));
            listBox.addView(tv);
            return;
        }
        for (int i = 0; i < filtered.size(); i++) {
            final BodyguardPolicy.SignalEntry s = filtered.get(i);
            listBox.addView(baselineSignalRow(s, listBox, allEntries));
        }
    }

    private View baselineSignalRow(final BodyguardPolicy.SignalEntry s,
                                   final LinearLayout listBox,
                                   final List<BodyguardPolicy.SignalEntry> allEntries) {
        boolean open = s.key.equals(expandedSignal);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        row.setBackground(roundBox(CARD2, open ? CYAN : STROKE, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 5);
        row.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView keyTv = txt(s.key, 10, FG);
        keyTv.setTypeface(Typeface.MONOSPACE);
        keyTv.setSingleLine(true);
        keyTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        keyTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(keyTv);

        int bColor = s.custom ? AMBER
                : BodyguardPolicy.STATUS_TRIPWIRE.equals(s.status) ? RED
                : BodyguardPolicy.STATUS_HOOKED.equals(s.status) ? GREEN : MUTE;
        String bText = s.custom ? Lang.t("用户加白", "Custom", "許可")
                : BodyguardPolicy.STATUS_TRIPWIRE.equals(s.status) ? Lang.t("绊线", "Trip", "罠")
                : BodyguardPolicy.STATUS_HOOKED.equals(s.status) ? Lang.t("已接管", "Hooked", "済")
                : Lang.t("豁免", "Exempt", "免除");
        TextView badge = pillText(bText, 8.5f, bColor, CARD, bColor);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(this, 6);
        badge.setLayoutParams(blp);
        top.addView(badge);
        row.addView(top);

        if (open) {
            LinearLayout detail = new LinearLayout(this);
            detail.setOrientation(LinearLayout.HORIZONTAL);
            detail.setGravity(Gravity.CENTER_VERTICAL);
            detail.setPadding(0, dp(this, 7), 0, 0);
            String info = (s.owner.isEmpty() ? s.gate : s.owner)
                    + (s.version.isEmpty() ? "" : " · v" + s.version);
            TextView infoTv = txt(info, 9, DIM);
            infoTv.setTypeface(Typeface.MONOSPACE);
            infoTv.setSingleLine(true);
            infoTv.setEllipsize(TextUtils.TruncateAt.END);
            infoTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            detail.addView(infoTv);

            if (s.custom) {
                TextView rm = pillText(Lang.t("移除", "Revoke", "削除"), 9, RED, CARD, RED);
                bindPress(rm);
                rm.setOnClickListener(v -> {
                    BodyguardStore.removeWhitelist(this, target.id(), s.key);
                    expandedSignal = null;
                    if (actions != null) actions.onBodyguardChanged();
                    Toast.makeText(this, Lang.t("已移除加白", "Removed"), Toast.LENGTH_SHORT).show();
                    rebuild(false);
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(this, 6);
                rm.setLayoutParams(rlp);
                detail.addView(rm);
            }
            row.addView(detail);
        }

        bindPress(row);
        row.setOnClickListener(v -> {
            expandedSignal = open ? null : s.key;
            populateBaselineList(listBox, allEntries);
        });
        return row;
    }

    // =========================================================================
    // DRILL-DOWN 2: 6-GATE INSPECTOR (监控闸口可点可查)
    // =========================================================================

    private void renderGatesInspector(LinearLayout host) {
        final String mode = BodyguardStore.mode(this, userId);
        final boolean armed = !BodyguardPolicy.MODE_OFF.equals(mode);
        final BodyguardPolicy.TargetBaseline base =
                BodyguardStore.baselineWithCustom(this, target.id());
        final Map<String, Integer> counts = BodyguardPolicy.gateCounts(base.entries);

        LinearLayout drawer = drawerBox(
                Lang.t("防护项", "Guards", "保護項目") + " · " + BodyguardPolicy.ALL_GATES.size(),
                () -> {
                    subView = null;
                    rebuild(false);
                });

        for (int i = 0; i < BodyguardPolicy.ALL_GATES.size(); i++) {
            final String gate = BodyguardPolicy.ALL_GATES.get(i);
            final boolean isIpGate = BodyguardPolicy.GATE_IP_FLIP.equals(gate);
            int cnt = counts.containsKey(gate) ? counts.get(gate).intValue() : 0;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
            card.setBackground(roundBox(isIpGate ? 0xFF132428 : CARD2,
                    isIpGate ? 0xFF2B454B : STROKE, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i == 0) lp.topMargin = dp(this, 6);
            if (i < BodyguardPolicy.ALL_GATES.size() - 1) lp.bottomMargin = dp(this, 5);
            card.setLayoutParams(lp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = txt(gateTitle(gate), 10.5f, armed ? GREEN : DIM);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(name);

            TextView code = txt(isIpGate ? "" : " " + gate, 8.5f, MUTE);
            code.setTypeface(Typeface.MONOSPACE);
            code.setSingleLine(true);
            code.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(code);

            if (isIpGate) {
                String expIso = actions != null ? IpGuardPolicy.normalizeIso(actions.activeRegionIso()) : "";
                boolean ipLocked = BodyguardStore.isLocked(this, target.id())
                        && IpGuardPolicy.isIpFlipSignal(BodyguardStore.lockReason(this, target.id()));
                IpGuardPolicy.Config cfg = BodyguardStore.ipGuardConfig(this, userId);
                String stText;
                int stColor;
                int stFill;
                int stStroke;
                if (!armed || !cfg.anyEnabled()) {
                    stText = Lang.t("关", "Off", "切");
                    stColor = DIM; stFill = CARD; stStroke = STROKE;
                } else if (ipLocked) {
                    stText = Lang.t("已拦住", "Blocked", "遮断");
                    stColor = 0xFFFCA5A5; stFill = 0x22E55353; stStroke = RED;
                } else if (!RootShell.rootReady() || BodyguardPolicy.MODE_OBSERVE.equals(mode)) {
                    stText = (expIso.isEmpty() ? "" : expIso + " · ") + Lang.t("提醒", "Alert", "通知");
                    stColor = AMBER; stFill = 0x1AE5A93B; stStroke = 0x66E5A93B;
                } else {
                    stText = (expIso.isEmpty() ? Lang.t("待选地区", "Idle", "待機")
                            : expIso + " · " + Lang.t("保护中", "Active", "保護中"));
                    stColor = expIso.isEmpty() ? DIM : GREEN;
                    stFill = expIso.isEmpty() ? CARD : 0x1634D399;
                    stStroke = expIso.isEmpty() ? STROKE : 0x5934D399;
                }
                top.addView(pillText(stText, 9, stColor, stFill, stStroke));
                TextView chev = txt(" ›", 12, SAND);
                top.addView(chev);
            } else {
                TextView countTv = txt(String.valueOf(cnt), 10, CYAN);
                countTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                top.addView(countTv);
            }
            card.addView(top);

            if (!isIpGate) {
                TextView sub = txt(gateScope(gate), 9, DIM);
                sub.setTypeface(Typeface.MONOSPACE);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                sub.setPadding(0, dp(this, 2), 0, 0);
                card.addView(sub);
            }

            bindPress(card);
            card.setOnClickListener(v -> {
                if (isIpGate) {
                    openIpGuardSheet();
                    return;
                }
                baselineFilter = gate;
                baselineQuery = "";
                expandedSignal = null;
                subView = SUB_BASELINE;
                rebuild(true);
            });
            drawer.addView(card);
        }
        host.addView(drawer);
    }

    private void openIpGuardSheet() {
        String expIso = actions != null ? actions.activeRegionIso() : "";
        String[] base = RootActions.readLastTargetIp(target);
        IpGuardPolicy.Config cur = BodyguardStore.ipGuardConfig(this, userId);
        IpGuardSheet.show(this, expIso, base[0], RootShell.rootReady(), cur, next -> {
            BodyguardStore.setIpGuardConfig(this, userId, next);
            if (actions != null) {
                actions.onBodyguardChanged();
            }
            rebuild(false);
        });
    }

    private String formatLockTitle(String reason, String targetName) {
        if ("net:vpn-down".equals(reason)) {
            return Lang.t("代理断开了 · 已禁止 " + targetName + " 联网",
                    "Proxy dropped · " + targetName + " blocked",
                    "プロキシ切断・" + targetName + " の通信を遮断");
        }
        if (reason != null && reason.startsWith("egress:direct")) {
            return Lang.t("走了本地直连 · 已禁止 " + targetName + " 联网",
                    "Direct bypass · " + targetName + " blocked",
                    "直接通信検出・" + targetName + " の通信を遮断");
        }
        if (reason != null && reason.startsWith("ip:flip:")) {
            return Lang.t("IP 已变动 · 已禁止 " + targetName + " 联网",
                    "IP changed · " + targetName + " blocked",
                    "IP 変化・" + targetName + " の通信を遮断");
        }
        String iso = IpGuardPolicy.parseTargetIsoFromSignal(reason);
        if (!iso.isEmpty()) {
            return Lang.t("IP 变到 " + IpGuardSheet.regionDisplay(iso) + " · 已禁止联网",
                    "IP in " + iso + " · " + targetName + " blocked",
                    "IP が " + iso + " に変化・通信遮断");
        }
        return Lang.t("已拦住 · 进程已锁定", "Blocked · Locked", "遮断済・ロック");
    }

    private String formatLockSub(String reason, String failHint) {
        if (failHint != null && !failHint.isEmpty()) {
            return failHint;
        }
        if ("net:vpn-down".equals(reason)) {
            return Lang.t("恢复代理后点右侧解锁", "Restore proxy, then unlock", "プロキシ復旧後に解除");
        }
        if (reason != null && reason.startsWith("egress:direct:")) {
            return reason.substring("egress:direct:".length())
                    + Lang.t(" 未走代理", " bypassed proxy", " が直接通信");
        }
        if (reason != null && reason.startsWith("ip:flip:")) {
            return Privacy.mask(reason.substring("ip:flip:".length()).replace("->", " → "));
        }
        if (IpGuardPolicy.isIpFlipSignal(reason)) {
            return Lang.t("切回节点后点右侧解锁", "Switch node, then unlock", "ノード切替後に解除");
        }
        return "";
    }

    private String formatIpIncidentTitle(String signalKey) {
        if ("net:vpn-down".equals(signalKey)) {
            return Lang.t("代理断开", "Proxy dropped", "プロキシ切断");
        }
        if (signalKey != null && signalKey.startsWith("egress:direct")) {
            return Lang.t("走了本地直连", "Used local network", "直接通信を検出");
        }
        if (signalKey != null && signalKey.startsWith("ip:flip:")) {
            return Lang.t("IP 变动", "IP changed", "IP 変化");
        }
        String iso = IpGuardPolicy.parseTargetIsoFromSignal(signalKey);
        if (!iso.isEmpty()) {
            return Lang.t("IP 变到 " + IpGuardSheet.regionDisplay(iso),
                    "IP changed to " + iso,
                    "IP が " + iso + " に変化");
        }
        return signalKey;
    }

    private String formatIpIncidentDetail(String signalKey) {
        if ("net:vpn-down".equals(signalKey)) {
            return Lang.t(" · 检测到无代理", " · No active proxy", " ・プロキシなし");
        }
        if (signalKey != null && signalKey.startsWith("egress:direct:")) {
            return " · " + signalKey.substring("egress:direct:".length());
        }
        if (signalKey != null && signalKey.startsWith("ip:flip:")) {
            String pair = signalKey.substring("ip:flip:".length()).replace("->", " → ");
            return " · " + Privacy.mask(pair);
        }
        String expIso = actions != null ? IpGuardPolicy.normalizeIso(actions.activeRegionIso()) : "";
        if (!expIso.isEmpty()) {
            return Lang.t(" · 目标 " + IpGuardSheet.regionDisplay(expIso),
                    " · Expected " + expIso,
                    " ・対象 " + expIso);
        }
        return "";
    }

    // =========================================================================
    // TAB 2: PERSONA-MATCH LEDGER (报表)
    // =========================================================================

    private void renderLedger(LinearLayout host) {
        final PersonaMatchEngine.MatchReport report =
                LedgerIngestor.buildReport(this, target.id(), userId);
        final PersonaMatchEngine.PersonaSnapshot expected =
                LedgerIngestor.currentExpectedSnapshot(this, target.id(), userId);
        final List<DiagnosticArchiveParser.HttpEvent> httpEvents =
                LedgerIngestor.loadHttpEvents(this, target.id(), userId, 30);
        final List<DiagnosticArchiveParser.DimSignal> diagSignals =
                LedgerIngestor.loadLatestDiagSignals(this, target.id(), userId);

        int leaks = report.leakCount + report.taintedCount;
        int scoreColor = leaks > 0 ? RED
                : report.driftCount > 0 ? AMBER
                : report.totalEvaluated > 0 ? GREEN : MUTE;

        // 1. Hero Score Row (flat inside unified card)
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.bottomMargin = dp(this, 10);
        hero.setLayoutParams(hlp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.BOTTOM);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView pct = txt(report.totalEvaluated > 0 ? report.matchPercent + "%" : "—",
                20, scoreColor);
        pct.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(pct);

        TextView ratio = txt(" " + report.matchedCount + "/" + report.totalEvaluated, 10, DIM);
        ratio.setTypeface(Typeface.MONOSPACE);
        ratio.setPadding(dp(this, 2), 0, 0, dp(this, 2));
        left.addView(ratio);
        hero.addView(left);

        // Right: leak badge + drift badge + Export
        TextView leakBdg = pillText(leaks + " " + Lang.t("禁区", "leak", "制限"), 9,
                leaks > 0 ? RED : MUTE, leaks > 0 ? 0x22E55353 : CARD2,
                leaks > 0 ? RED : STROKE);
        leakBdg.setTypeface(Typeface.MONOSPACE);
        hero.addView(leakBdg);

        TextView driftBdg = pillText(report.driftCount + " " + Lang.t("漂移", "drift", "ズレ"), 9,
                report.driftCount > 0 ? AMBER : MUTE,
                report.driftCount > 0 ? 0x22E5A93B : CARD2,
                report.driftCount > 0 ? AMBER : STROKE);
        driftBdg.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = dp(this, 5);
        driftBdg.setLayoutParams(dlp);
        hero.addView(driftBdg);

        TextView exportBtn = pillText(Lang.t("导出", "Export", "出力"), 9.5f, FG, CARD2, CYAN);
        exportBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = dp(this, 6);
        exportBtn.setLayoutParams(elp);
        bindPress(exportBtn);
        exportBtn.setOnClickListener(v -> exportLedgerMarkdown(report, expected, httpEvents, diagSignals));
        hero.addView(exportBtn);

        host.addView(hero);

        if (report.rows.isEmpty() && httpEvents.isEmpty() && diagSignals.isEmpty()) {
            host.addView(cardDivider(2, 9));
            TextView tv = txt(Lang.t("暂无上传观测", "No upload observations yet", "観測履歴なし"),
                    10, MUTE);
            tv.setTypeface(Typeface.MONOSPACE);
            host.addView(tv);
            return;
        }

        // 3. Separate Abnormal vs Matched vs Unobserved
        List<PersonaMatchEngine.FieldRow> abnormal = new ArrayList<PersonaMatchEngine.FieldRow>();
        List<PersonaMatchEngine.FieldRow> matched = new ArrayList<PersonaMatchEngine.FieldRow>();
        List<PersonaMatchEngine.FieldRow> unobserved = new ArrayList<PersonaMatchEngine.FieldRow>();
        for (PersonaMatchEngine.FieldRow r : report.rows) {
            if (PersonaMatchEngine.VERDICT_MATCH.equals(r.verdict)) {
                matched.add(r);
            } else if (PersonaMatchEngine.VERDICT_UNOBSERVED.equals(r.verdict)) {
                unobserved.add(r);
            } else {
                abnormal.add(r);
            }
        }
        if (!abnormal.isEmpty()) {
            // Show 3-Column Header only when rows are directly visible above fold
            LinearLayout hdr = new LinearLayout(this);
            hdr.setOrientation(LinearLayout.HORIZONTAL);
            hdr.setPadding(dp(this, 10), 0, dp(this, 10), dp(this, 6));
            hdr.addView(colText(Lang.t("字段", "Field", "項目"), 9.5f, MUTE, 1.2f, Gravity.START));
            hdr.addView(colText(Lang.t("当前人设", "Persona", "人設"), 9.5f, MUTE, 0.85f, Gravity.START));
            hdr.addView(colText(Lang.t("最近已上传", "Uploaded", "送信済"), 9.5f, MUTE, 1.3f, Gravity.END));
            host.addView(hdr);
            for (PersonaMatchEngine.FieldRow r : abnormal) {
                host.addView(ledgerRowView(r));
            }
        }

        // 4. Collapsible Matched Group (CARD2 tile inside big card)
        if (!matched.isEmpty()) {
            LinearLayout toggle = new LinearLayout(this);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
            toggle.setBackground(roundBox(ledgerMatchedOpen ? 0xFF19282C : CARD2,
                    ledgerMatchedOpen ? CYAN : STROKE, 12));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = abnormal.isEmpty() ? 0 : dp(this, 4);
            tlp.bottomMargin = (ledgerMatchedOpen || !unobserved.isEmpty()) ? dp(this, 6) : 0;
            toggle.setLayoutParams(tlp);

            TextView lbl = txt(Lang.t("已一致", "Matched", "一致") + " (" + matched.size() + ")",
                    10.5f, GREEN);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(lbl);

            bindPress(toggle);
            toggle.setOnClickListener(v -> {
                ledgerMatchedOpen = !ledgerMatchedOpen;
                rebuild(false);
            });
            host.addView(toggle);

            if (ledgerMatchedOpen) {
                for (PersonaMatchEngine.FieldRow r : matched) {
                    host.addView(ledgerRowView(r));
                }
            }
        }

        // 4b. Collapsible Unobserved Group (never masquerade unobserved fields as anomalies)
        if (!unobserved.isEmpty()) {
            LinearLayout toggle = new LinearLayout(this);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
            toggle.setBackground(roundBox(ledgerUnobservedOpen ? 0xFF19282C : CARD2,
                    ledgerUnobservedOpen ? CYAN : STROKE, 12));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.bottomMargin = ledgerUnobservedOpen ? dp(this, 6) : 0;
            toggle.setLayoutParams(tlp);

            TextView lbl = txt(Lang.t("未观测", "Unobserved", "未観測") + " (" + unobserved.size() + ")",
                    10.5f, DIM);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(lbl);

            bindPress(toggle);
            toggle.setOnClickListener(v -> {
                ledgerUnobservedOpen = !ledgerUnobservedOpen;
                rebuild(false);
            });
            host.addView(toggle);

            if (ledgerUnobservedOpen) {
                for (PersonaMatchEngine.FieldRow r : unobserved) {
                    host.addView(ledgerRowView(r));
                }
            }
        }

        // 4c. Collapsible Target HTTP Response Archive (structured from Sentry breadcrumbs)
        if (!httpEvents.isEmpty()) {
            int httpErrs = 0;
            String latestPop = "";
            for (DiagnosticArchiveParser.HttpEvent e : httpEvents) {
                if (e.isError()) httpErrs++;
                if (latestPop.isEmpty() && !e.edgePop.isEmpty()) latestPop = e.edgePop;
            }
            LinearLayout toggle = new LinearLayout(this);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
            toggle.setBackground(roundBox(ledgerHttpOpen ? 0xFF19282C : CARD2,
                    ledgerHttpOpen ? CYAN : (httpErrs > 0 ? AMBER : STROKE), 12));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(this, 6);
            tlp.bottomMargin = ledgerHttpOpen ? dp(this, 6) : 0;
            toggle.setLayoutParams(tlp);

            TextView lbl = txt(Lang.t("HTTP 响应", "HTTP Responses", "HTTP 応答")
                    + " (" + httpEvents.size() + ")", 10.5f, httpErrs > 0 ? AMBER : CYAN);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(lbl);

            String rightMeta = httpErrs > 0
                    ? httpErrs + " " + Lang.t("异常", "err", "異常")
                    : (!latestPop.isEmpty() ? latestPop : "2xx");
            int metaColor = httpErrs > 0 ? RED : GREEN;
            TextView rPill = pillText(rightMeta, 9, metaColor,
                    httpErrs > 0 ? 0x22E55353 : 0x1A34D399,
                    httpErrs > 0 ? RED : 0x5534D399);
            rPill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            toggle.addView(rPill);

            bindPress(toggle);
            toggle.setOnClickListener(v -> {
                ledgerHttpOpen = !ledgerHttpOpen;
                rebuild(false);
            });
            host.addView(toggle);

            if (ledgerHttpOpen) {
                for (DiagnosticArchiveParser.HttpEvent e : httpEvents) {
                    host.addView(httpEventRowView(e));
                }
            }
        }

        // 4d. Collapsible Multi-Dimension Target Staging Diagnostics (Net / Env / Datadog / Resp / Crash)
        if (!diagSignals.isEmpty()) {
            boolean diagAlert = false;
            for (DiagnosticArchiveParser.DimSignal s : diagSignals) {
                if (diagSignalColor(s) == RED || diagSignalColor(s) == AMBER) {
                    diagAlert = true;
                    break;
                }
            }
            LinearLayout toggle = new LinearLayout(this);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
            toggle.setBackground(roundBox(ledgerDiagOpen ? 0xFF19282C : CARD2,
                    ledgerDiagOpen ? CYAN : (diagAlert ? AMBER : STROKE), 12));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(this, 6);
            tlp.bottomMargin = ledgerDiagOpen ? dp(this, 6) : 0;
            toggle.setLayoutParams(tlp);

            TextView lbl = txt(Lang.t("暂存诊断", "Staging Archive", "診断キャッシュ")
                    + " (" + diagSignals.size() + ")", 10.5f, diagAlert ? AMBER : FG);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(lbl);

            TextView rPill = pillText(
                    diagAlert ? Lang.t("有告警", "alert", "警告") : Lang.t("已拆解", "parsed", "解析済"),
                    9, diagAlert ? AMBER : DIM,
                    diagAlert ? 0x22E5A93B : CARD, diagAlert ? AMBER : STROKE);
            rPill.setTypeface(Typeface.MONOSPACE);
            toggle.addView(rPill);

            bindPress(toggle);
            toggle.setOnClickListener(v -> {
                ledgerDiagOpen = !ledgerDiagOpen;
                rebuild(false);
            });
            host.addView(toggle);

            if (ledgerDiagOpen) {
                for (DiagnosticArchiveParser.DimSignal s : diagSignals) {
                    host.addView(diagSignalRowView(s));
                }
            }
        }

        // 5. Divider + Quiet Footer Action
        host.addView(cardDivider(10, 8));
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView footMeta = txt(report.matchedCount + "/" + report.totalEvaluated
                + " " + Lang.t("项已对齐", "aligned", "件一致"), 9.5f, DIM);
        footMeta.setTypeface(Typeface.MONOSPACE);
        footMeta.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(footMeta);

        TextView clearBtn = txt(Lang.t("清空报表", "Clear ledger", "履歴クリア"), 9.5f, MUTE);
        clearBtn.setPadding(dp(this, 6), dp(this, 2), 0, dp(this, 2));
        bindPress(clearBtn);
        clearBtn.setOnClickListener(v -> confirmClearLedger());
        footer.addView(clearBtn);
        host.addView(footer);
    }

    private View ledgerRowView(final PersonaMatchEngine.FieldRow r) {
        final boolean open = r.fieldKey.equals(expandedLedgerRow);
        final String revKey = "ledger:" + r.fieldKey;
        final boolean revealed = REVEALED_IDS.contains(revKey);
        final int vColor = ledgerVerdictColor(r.verdict);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        box.setBackground(roundBox(open ? 0xFF19282C : CARD2, open ? CYAN : STROKE, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 6);
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        // Col 1: field key (explicit right margin prevents collision with Col 2)
        TextView kTv = txt(r.fieldKey, 10, FG);
        kTv.setTypeface(Typeface.MONOSPACE);
        kTv.setSingleLine(true);
        kTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -2, 1.2f);
        klp.rightMargin = dp(this, 8);
        kTv.setLayoutParams(klp);
        top.addView(kTv);

        // Col 2: expected
        String expStr = r.expectedValue.isEmpty() ? "—"
                : "reset".equals(r.expectedValue) ? Lang.t("需重置", "reset", "要再生成")
                : "clean".equals(r.expectedValue) ? Lang.t("洁净", "clean", "正常")
                : r.expectedValue;
        TextView eTv = colText(expStr, 9.5f, DIM, 0.85f, Gravity.START);
        eTv.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) eTv.getLayoutParams();
        elp.rightMargin = dp(this, 6);
        eTv.setLayoutParams(elp);
        top.addView(eTv);

        // Col 3: uploaded value + verdict tag
        LinearLayout c3 = new LinearLayout(this);
        c3.setOrientation(LinearLayout.HORIZONTAL);
        c3.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        c3.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.3f));

        String shownVal = r.latestValue.isEmpty() ? "—"
                : (r.isIdentityField && !revealed) ? r.maskedLatestValue : r.latestValue;
        TextView uTv = txt(shownVal, 9.5f, vColor);
        uTv.setTypeface(Typeface.MONOSPACE);
        uTv.setSingleLine(true);
        uTv.setEllipsize(TextUtils.TruncateAt.END);
        uTv.setGravity(Gravity.END);
        uTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        c3.addView(uTv);

        String tag = ledgerVerdictTag(r.verdict);
        if (!tag.isEmpty()) {
            TextView tTv = pillText(tag, 8, vColor, CARD, vColor);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(this, 5);
            tTv.setLayoutParams(tlp);
            c3.addView(tTv);
        }
        top.addView(c3);
        box.addView(top);

        // Expandable Timeline + Full UUID Toggle
        if (open) {
            View div = new View(this);
            div.setBackgroundColor(STROKE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(this, 1));
            dlp.topMargin = dp(this, 8);
            dlp.bottomMargin = dp(this, 6);
            div.setLayoutParams(dlp);
            box.addView(div);

            if (r.isIdentityField && !r.latestValue.isEmpty()) {
                LinearLayout idBar = new LinearLayout(this);
                idBar.setOrientation(LinearLayout.HORIZONTAL);
                idBar.setGravity(Gravity.CENTER_VERTICAL);
                idBar.setPadding(0, 0, 0, dp(this, 6));

                TextView fullTv = txt(revealed ? r.latestValue : r.maskedLatestValue, 9.5f, FG);
                fullTv.setTypeface(Typeface.MONOSPACE);
                fullTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                idBar.addView(fullTv);

                TextView revBtn = pillText(
                        revealed ? Lang.t("脱敏", "Mask", "隠す") : Lang.t("完整", "Full", "全文"),
                        8.5f, CYAN, CARD2, STROKE);
                bindPress(revBtn);
                revBtn.setOnClickListener(v -> {
                    if (REVEALED_IDS.contains(revKey)) {
                        REVEALED_IDS.remove(revKey);
                    } else {
                        REVEALED_IDS.add(revKey);
                    }
                    rebuild(false);
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = dp(this, 6);
                revBtn.setLayoutParams(rlp);
                idBar.addView(revBtn);
                box.addView(idBar);
            }

            if (r.history.isEmpty()) {
                TextView none = txt(Lang.t("尚无历史上传", "No history", "履歴なし"), 9, MUTE);
                none.setTypeface(Typeface.MONOSPACE);
                box.addView(none);
            } else {
                int maxHist = Math.min(r.history.size(), 8);
                for (int i = 0; i < maxHist; i++) {
                    PersonaMatchEngine.HistoryItem h = r.history.get(i);
                    LinearLayout hRow = new LinearLayout(this);
                    hRow.setOrientation(LinearLayout.HORIZONTAL);
                    hRow.setPadding(0, dp(this, 2), 0, dp(this, 2));

                    TextView ts = txt(formatTime(h.lastSeen) + " · " + h.sourceSdk
                            + (h.seenCount > 1 ? " ×" + h.seenCount : ""), 9, MUTE);
                    ts.setTypeface(Typeface.MONOSPACE);
                    ts.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    hRow.addView(ts);

                    String hv = (r.isIdentityField && !revealed) ? h.maskedValue : h.observedValue;
                    TextView hVal = txt(hv, 9, ledgerVerdictColor(h.verdict));
                    hVal.setTypeface(Typeface.MONOSPACE);
                    hVal.setSingleLine(true);
                    hVal.setEllipsize(TextUtils.TruncateAt.END);
                    hRow.addView(hVal);
                    box.addView(hRow);
                }
            }
        }

        bindPress(box);
        box.setOnClickListener(v -> {
            expandedLedgerRow = open ? null : r.fieldKey;
            rebuild(false);
        });
        return box;
    }

    private View httpEventRowView(final DiagnosticArchiveParser.HttpEvent e) {
        final boolean open = e.dedupKey.equals(expandedHttpKey);
        final boolean unknownStatus = e.statusCode == 0 && !e.isError();
        int stColor = unknownStatus ? DIM
                : e.isError() ? (e.statusCode >= 500 || e.statusCode == 0 ? RED : AMBER)
                : GREEN;
        String stText = e.statusCode > 0 ? String.valueOf(e.statusCode)
                : (unknownStatus ? "—" : "ERR");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        box.setBackground(roundBox(open ? 0xFF19282C : CARD2, open ? CYAN : STROKE, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 5);
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        int stFill = unknownStatus ? CARD
                : stColor == GREEN ? 0x1A34D399
                : (stColor == AMBER ? 0x22E5A93B : 0x22E55353);
        int stStroke = unknownStatus ? STROKE : (stColor == GREEN ? 0x5534D399 : stColor);
        TextView stBdg = pillText(stText, 9, stColor, stFill, stStroke);
        stBdg.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.rightMargin = dp(this, 7);
        stBdg.setLayoutParams(slp);
        top.addView(stBdg);

        TextView pathTv = txt(e.method + " " + e.path, 10, FG);
        pathTv.setTypeface(Typeface.MONOSPACE);
        pathTv.setSingleLine(true);
        pathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
        plp.rightMargin = dp(this, 6);
        pathTv.setLayoutParams(plp);
        top.addView(pathTv);

        StringBuilder meta = new StringBuilder();
        if (!e.edgePop.isEmpty()) meta.append(e.edgePop);
        if (e.durationMs > 0L) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(e.durationMs).append("ms");
        }
        if (meta.length() == 0 && e.occurredAt > 0L) {
            meta.append(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(e.occurredAt)));
        }
        TextView mTv = txt(meta.toString(), 9, !e.edgePop.isEmpty() ? CYAN : DIM);
        mTv.setTypeface(Typeface.MONOSPACE);
        mTv.setSingleLine(true);
        top.addView(mTv);
        box.addView(top);

        if (open) {
            box.addView(cardDivider(7, 6));
            StringBuilder det = new StringBuilder();
            det.append(e.host);
            if (!e.protocol.isEmpty()) det.append(" · ").append(e.protocol);
            if (!e.tlsDiverted.isEmpty()) det.append(" · tls_diverted=").append(e.tlsDiverted);
            if (e.occurredAt > 0L) {
                det.append(" · ").append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
                        .format(new Date(e.occurredAt)));
            }
            TextView d1 = txt(det.toString(), 9, DIM);
            d1.setTypeface(Typeface.MONOSPACE);
            box.addView(d1);

            if (!e.cfRay.isEmpty() || !e.requestId.isEmpty()) {
                StringBuilder ids = new StringBuilder();
                if (!e.cfRay.isEmpty()) ids.append("cf_ray=").append(e.cfRay);
                if (!e.requestId.isEmpty()) {
                    if (ids.length() > 0) ids.append("\n");
                    ids.append("req=").append(e.requestId);
                }
                TextView d2 = txt(ids.toString(), 8.5f, MUTE);
                d2.setTypeface(Typeface.MONOSPACE);
                d2.setPadding(0, dp(this, 3), 0, 0);
                box.addView(d2);
            }
            if (!e.errorMessage.isEmpty()) {
                TextView errTv = txt(e.errorMessage, 9, RED);
                errTv.setTypeface(Typeface.MONOSPACE);
                errTv.setPadding(0, dp(this, 3), 0, 0);
                box.addView(errTv);
            }
        }

        bindPress(box);
        box.setOnClickListener(v -> {
            expandedHttpKey = open ? null : e.dedupKey;
            rebuild(false);
        });
        return box;
    }

    private View diagSignalRowView(final DiagnosticArchiveParser.DimSignal s) {
        final String rowKey = s.category + "|" + s.signalKey;
        final boolean open = rowKey.equals(expandedDiagKey);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        box.setBackground(roundBox(open ? 0xFF19282C : CARD2, open ? CYAN : STROKE, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 5);
        box.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String catTag = diagCatTag(s.category);
        TextView bdg = pillText(catTag, 8.5f, DIM, CARD, STROKE);
        bdg.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.rightMargin = dp(this, 7);
        bdg.setLayoutParams(blp);
        row.addView(bdg);

        TextView kTv = txt(diagSignalLabel(s.signalKey), 10, FG);
        kTv.setSingleLine(true);
        kTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -2, 1f);
        klp.rightMargin = dp(this, 6);
        kTv.setLayoutParams(klp);
        row.addView(kTv);

        TextView vTv = txt(s.signalValue, 9.5f, diagSignalColor(s));
        vTv.setTypeface(Typeface.MONOSPACE);
        vTv.setSingleLine(true);
        vTv.setEllipsize(TextUtils.TruncateAt.END);
        vTv.setGravity(Gravity.END);
        vTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.1f));
        row.addView(vTv);
        box.addView(row);

        if (open) {
            box.addView(cardDivider(7, 6));
            TextView fullVal = txt(s.signalValue, 9.5f, diagSignalColor(s));
            fullVal.setTypeface(Typeface.MONOSPACE);
            box.addView(fullVal);
            String meta = s.sourceFile + " · ×" + s.seenCount
                    + (s.lastSeen > 0L ? " · " + new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                            .format(new Date(s.lastSeen)) : "");
            TextView mTv = txt(meta, 8.5f, MUTE);
            mTv.setTypeface(Typeface.MONOSPACE);
            mTv.setPadding(0, dp(this, 3), 0, 0);
            box.addView(mTv);
        }

        bindPress(box);
        box.setOnClickListener(v -> {
            expandedDiagKey = open ? null : rowKey;
            rebuild(false);
        });
        return box;
    }

    private static String diagCatTag(String cat) {
        if (DiagnosticArchiveParser.CAT_NETWORK.equals(cat)) return "NET";
        if (DiagnosticArchiveParser.CAT_DATADOG.equals(cat)) return "DD";
        if (DiagnosticArchiveParser.CAT_RESPONSE.equals(cat)) return "RESP";
        if (DiagnosticArchiveParser.CAT_CRASH.equals(cat)) return "ERR";
        return "ENV";
    }

    private static int diagSignalColor(DiagnosticArchiveParser.DimSignal s) {
        if ("vpn_active".equals(s.signalKey) || "tls_diverted".equals(s.signalKey)) {
            return "true".equalsIgnoreCase(s.signalValue) ? RED : GREEN;
        }
        if ("pending_envelopes".equals(s.signalKey) || "recent_error".equals(s.signalKey)) {
            return AMBER;
        }
        if ("cf_edge_pop".equals(s.signalKey) || "datadog_locale".equals(s.signalKey)) {
            return CYAN;
        }
        return FG;
    }

    private static String diagSignalLabel(String key) {
        if ("vpn_active".equals(key)) return Lang.t("VPN 视角", "VPN visible", "VPN 検知");
        if ("network_type".equals(key)) return Lang.t("网络类型", "Network type", "回線種別");
        if ("tls_diverted".equals(key)) return Lang.t("TLS 劫持检测", "TLS diverted", "TLS 介入");
        if ("cf_edge_pop".equals(key)) return Lang.t("CF 边缘节点", "CF edge POP", "CF エッジ");
        if ("sign_in_browser".equals(key)) return Lang.t("登录浏览器", "Sign-in browser", "認証ブラウザ");
        if ("sign_in_custom_tabs".equals(key)) return Lang.t("Custom Tabs", "Custom Tabs", "Custom Tabs");
        if ("cronet_provider".equals(key)) return Lang.t("Cronet 引擎", "Cronet engine", "Cronet");
        if ("webview_runtime".equals(key)) return Lang.t("WebView 内核", "WebView", "WebView");
        if ("play_services_status".equals(key)) return Lang.t("GMS 状态", "GMS status", "GMS 状態");
        if ("datadog_locale".equals(key)) return Lang.t("Datadog 语言", "Datadog locale", "Datadog 言語");
        if ("datadog_view".equals(key)) return Lang.t("Datadog 视图", "Datadog view", "Datadog 画面");
        if ("datadog_rum_counts".equals(key)) return Lang.t("Datadog 计数", "Datadog counts", "Datadog 集計");
        if ("datadog_service".equals(key)) return Lang.t("Datadog 服务", "Datadog service", "Datadog");
        if ("bootstrap_features".equals(key)) return Lang.t("Bootstrap 特性", "Bootstrap flags", "Bootstrap");
        if ("bootstrap_hash".equals(key)) return Lang.t("Bootstrap 哈希", "Bootstrap hash", "Bootstrap");
        if ("models_cached".equals(key)) return Lang.t("缓存模型数", "Cached models", "モデル数");
        if ("account_plan_cache".equals(key)) return Lang.t("账号套餐缓存", "Plan cache", "プラン");
        if ("account_country_cache".equals(key)) return Lang.t("账号国家缓存", "Country cache", "国キャッシュ");
        if ("statsig_cache_kb".equals(key)) return Lang.t("Statsig 缓存", "Statsig cache", "Statsig");
        if ("pending_envelopes".equals(key)) return Lang.t("待传异常信封", "Pending envelopes", "未送信エラー");
        if ("recent_error".equals(key)) return Lang.t("最近异常", "Recent error", "直近エラー");
        return key;
    }

    private void exportLedgerMarkdown(PersonaMatchEngine.MatchReport report,
                                      PersonaMatchEngine.PersonaSnapshot expected,
                                      List<DiagnosticArchiveParser.HttpEvent> httpEvents,
                                      List<DiagnosticArchiveParser.DimSignal> diagSignals) {
        try {
            File baseDir = getExternalFilesDir(null);
            if (baseDir == null) {
                baseDir = getFilesDir();
            }
            File dir = new File(baseDir, "reports");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Toast.makeText(this, Lang.t("导出目录创建失败", "Export failed"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File out = new File(dir, target.id() + "-u" + userId + "-" + stamp + ".md");
            StringBuilder mdBuf = new StringBuilder(
                    PersonaMatchEngine.exportMarkdown(report, expected, true));
            DiagnosticArchiveParser.appendMarkdown(mdBuf, httpEvents, diagSignals);
            String md = mdBuf.toString();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(md.getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            Toast.makeText(this, Lang.t("已导出: ", "Exported: ") + out.getName(),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, Lang.t("导出失败: ", "Export error: ") + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearLedger() {
        PersonaEditSheets.showConfirmSheet(
                this,
                Lang.t("清空本空间报表", "Clear Space Ledger", "空間の履歴をクリア"),
                target.displayName() + " · u" + userId,
                Lang.t("清空", "Clear", "クリア"),
                true,
                () -> {
                    LedgerDb.get(this).clearSpace(target.id(), userId);
                    expandedLedgerRow = null;
                    expandedHttpKey = null;
                    expandedDiagKey = null;
                    rebuild(false);
                });
    }

    // =========================================================================
    // TAB 3: 6-DIMENSION DIAGNOSTICS (诊断)
    // =========================================================================

    private void renderDiagnostics(LinearLayout host) {
        if (currentModel == null) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(this, 12), dp(this, 18), dp(this, 12), dp(this, 18));
            empty.setBackground(roundBox(CARD2, STROKE, 12));
            empty.addView(txt(Lang.t("扫描中…", "Scanning…"), 10.5f, DIM));
            host.addView(empty);
            return;
        }
        final Map<String, DiagnosticClassifier.DimensionGroup> groups =
                DiagnosticClassifier.classify(currentModel);

        // 1. 2x3 Six-Dimension Matrix (CARD2 inner tiles matching PersonaPanel / NetworkPanel)
        for (int rowIdx = 0; rowIdx < 3; rowIdx++) {
            LinearLayout gridRow = new LinearLayout(this);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.bottomMargin = rowIdx < 2 ? dp(this, 6) : 0;
            gridRow.setLayoutParams(glp);

            String leftId = DiagnosticClassifier.ALL_DIMS.get(rowIdx * 2);
            String rightId = DiagnosticClassifier.ALL_DIMS.get(rowIdx * 2 + 1);
            gridRow.addView(dimCard(groups.get(leftId), 0, dp(this, 6)));
            gridRow.addView(dimCard(groups.get(rightId), 0, 0));
            host.addView(gridRow);
        }

        // 2. Filter Bar + Dimension Quick Action
        final DiagnosticClassifier.DimensionGroup selected = groups.get(diagDim);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 10);
        blp.bottomMargin = dp(this, 8);
        bar.setLayoutParams(blp);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(this, 2), dp(this, 2), dp(this, 2), dp(this, 2));
        seg.setBackground(roundBox(BG, STROKE, 10));
        seg.addView(diagFilterBtn("ALL", Lang.t("本维", "Dim", "現在")));
        seg.addView(diagFilterBtn("ABNORMAL", Lang.t("仅异常", "Issues", "異常")));
        seg.addView(diagFilterBtn("BOUNDARY", Lang.t("硬边界", "Bound", "境界")));
        bar.addView(seg);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        if ("ALL".equals(diagFilter)) {
            View quickAction = dimQuickAction(diagDim);
            if (quickAction != null) {
                bar.addView(quickAction);
            }
        }
        host.addView(bar);

        // 3. Visible Entries Drawer (CARD2 container inside unified card)
        List<DiagnosticClassifier.Entry> visible = new ArrayList<DiagnosticClassifier.Entry>();
        if ("ALL".equals(diagFilter)) {
            if (selected != null) {
                visible.addAll(selected.entries);
            }
        } else {
            for (DiagnosticClassifier.DimensionGroup g : groups.values()) {
                for (DiagnosticClassifier.Entry e : g.entries) {
                    if ("ABNORMAL".equals(diagFilter)
                            && (DiagnosticClassifier.ST_RISK.equals(e.status)
                                    || DiagnosticClassifier.ST_WARN.equals(e.status))) {
                        visible.add(e);
                    } else if ("BOUNDARY".equals(diagFilter)
                            && DiagnosticClassifier.ST_BOUNDARY.equals(e.status)) {
                        visible.add(e);
                    }
                }
            }
        }

        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setPadding(dp(this, 11), dp(this, 4), dp(this, 11), dp(this, 4));
        listCard.setBackground(roundBox(CARD2, STROKE, 12));

        if (visible.isEmpty()) {
            TextView none = txt(Lang.t("当前无异常项", "All clear", "異常なし"), 10, MUTE);
            none.setTypeface(Typeface.MONOSPACE);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(this, 14), 0, dp(this, 14));
            listCard.addView(none);
        } else {
            for (int i = 0; i < visible.size(); i++) {
                if (i > 0) {
                    View div = new View(this);
                    div.setBackgroundColor(0x661E3136);
                    div.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(this, 1)));
                    listCard.addView(div);
                }
                listCard.addView(diagEntryRow(visible.get(i)));
            }
        }
        host.addView(listCard);
    }

    private View dimCard(final DiagnosticClassifier.DimensionGroup g, int leftM, int rightM) {
        boolean active = "ALL".equals(diagFilter) && g.id.equals(diagDim);
        int dotColor = g.riskCount > 0 ? RED
                : g.warnCount > 0 ? AMBER
                : (g.boundaryCount > 0 && g.okCount == 0) ? MUTE : GREEN;
        String summary = g.riskCount > 0 ? g.riskCount + " " + Lang.t("风险", "risk", "危険")
                : g.warnCount > 0 ? g.warnCount + " " + Lang.t("注意", "warn", "注意")
                : (g.boundaryCount > 0 && g.okCount == 0)
                        ? g.boundaryCount + " " + Lang.t("边界", "bound", "境界")
                : g.entries.size() + " " + Lang.t("项", "ok", "件");

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        tile.setBackground(roundBox(active ? 0xFF19282C : CARD2, active ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftM;
        lp.rightMargin = rightM;
        tile.setLayoutParams(lp);

        TextView name = txt(dimLabel(g.id), 9.5f, active ? FG : DIM);
        if (active) name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name);

        TextView sumTv = txt(summary, 11, dotColor);
        sumTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sumTv.setSingleLine(true);
        sumTv.setPadding(0, dp(this, 3), 0, 0);
        tile.addView(sumTv);

        bindPress(tile);
        tile.setOnClickListener(v -> {
            diagDim = g.id;
            diagFilter = "ALL";
            rebuild(false);
        });
        return tile;
    }

    private View diagFilterBtn(final String filterId, String label) {
        boolean on = filterId.equals(diagFilter);
        TextView tv = txt(label, 9.5f, on ? BG : DIM);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4));
        if (on) {
            tv.setBackground(roundBox(CYAN, CYAN, 8));
        }
        bindPress(tv);
        tv.setOnClickListener(v -> {
            diagFilter = filterId;
            rebuild(false);
        });
        return tv;
    }

    private View dimQuickAction(String dimId) {
        if (actions == null) {
            return null;
        }
        if (DiagnosticClassifier.DIM_STEALTH.equals(dimId)) {
            if (!legacyResidueDetected) {
                return null;
            }
            TextView btn = pillText(Lang.t("清理残留", "Scrub", "清掃"), 9.5f, RED, CARD2, RED);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            bindPress(btn);
            btn.setOnClickListener(v -> actions.scrubTargetResidue());
            return btn;
        }
        if (DiagnosticClassifier.DIM_IDENTITY.equals(dimId) && target.localIdentityWrites()) {
            TextView btn = pillText(Lang.t("重置身份链", "Reset IDs", "ID再生成"),
                    9.5f, CYAN, CARD2, CYAN);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            bindPress(btn);
            btn.setOnClickListener(v -> actions.resetLocalIdentity());
            return btn;
        }
        if (DiagnosticClassifier.DIM_ACCOUNT.equals(dimId) && "chatgpt".equals(target.id())) {
            TextView btn = pillText(Lang.t("编辑 hint", "Edit hint", "国コード"),
                    9.5f, CYAN, CARD2, CYAN);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            bindPress(btn);
            btn.setOnClickListener(v -> promptCountryHintEdit());
            return btn;
        }
        return null;
    }

    private View diagEntryRow(final DiagnosticClassifier.Entry e) {
        final CheckItem item = e.item;
        final String revKey = "diag:" + item.id();
        final boolean revealed = REVEALED_IDS.contains(revKey);
        final int stColor = DiagnosticClassifier.ST_RISK.equals(e.status) ? RED
                : DiagnosticClassifier.ST_WARN.equals(e.status) ? AMBER
                : (DiagnosticClassifier.ST_BOUNDARY.equals(e.status)
                        || DiagnosticClassifier.ST_SILENT.equals(e.status)) ? MUTE : GREEN;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(this, 8), 0, dp(this, 8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView kTv = txt(diagItemTitle(item.id()), 10.5f, FG);
        kTv.setSingleLine(true);
        kTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -2, 1.15f);
        klp.rightMargin = dp(this, 8);
        kTv.setLayoutParams(klp);
        top.addView(kTv);

        String valText = formatDiagValue(item, e.isIdentity, revealed);
        TextView vTv = txt(valText, 9.5f, item.isRead() ? stColor : MUTE);
        vTv.setTypeface(Typeface.MONOSPACE);
        vTv.setSingleLine(true);
        vTv.setEllipsize(TextUtils.TruncateAt.END);
        vTv.setGravity(Gravity.END);
        vTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.1f));
        top.addView(vTv);

        String tag = DiagnosticClassifier.ST_BOUNDARY.equals(e.status)
                ? Lang.t("硬边界", "Bound", "境界")
                : DiagnosticClassifier.ST_RISK.equals(e.status)
                        ? Lang.t("风险", "Risk", "危険")
                : DiagnosticClassifier.ST_WARN.equals(e.status)
                        ? Lang.t("注意", "Warn", "注意") : "";
        if (!tag.isEmpty()) {
            TextView bTv = pillText(tag, 8, stColor, CARD, stColor);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(this, 5);
            bTv.setLayoutParams(blp);
            top.addView(bTv);
        }
        row.addView(top);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setPadding(0, dp(this, 2), 0, 0);
        TextView src = txt(item.id(), 8.5f, MUTE);
        src.setTypeface(Typeface.MONOSPACE);
        src.setSingleLine(true);
        src.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        src.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bot.addView(src);

        if (e.isIdentity && item.isRead() && item.value().length() > 10) {
            TextView hint = txt(revealed ? Lang.t("脱敏", "mask", "隠す")
                    : Lang.t("完整", "full", "全文"), 8.5f, CYAN);
            hint.setTypeface(Typeface.MONOSPACE);
            bot.addView(hint);
            bindPress(row);
            row.setOnClickListener(v -> {
                if (REVEALED_IDS.contains(revKey)) {
                    REVEALED_IDS.remove(revKey);
                } else {
                    REVEALED_IDS.add(revKey);
                }
                rebuild(false);
            });
        } else if ("target.data.country_hint".equals(item.id()) && actions != null) {
            TextView editTag = txt(Lang.t("改", "edit", "編集"), 8.5f, CYAN);
            editTag.setTypeface(Typeface.DEFAULT_BOLD);
            bot.addView(editTag);
            bindPress(row);
            row.setOnClickListener(v -> promptCountryHintEdit());
        }
        row.addView(bot);
        return row;
    }

    private void promptCountryHintEdit() {
        PersonaEditSheets.showCountryHintSheet(
                this,
                "",
                iso -> {
                    if (actions != null) actions.editTargetCountryHint(iso);
                },
                () -> {
                    if (actions != null) actions.restoreTargetCountryHint();
                });
    }

    // =========================================================================
    // VISUAL PRIMITIVES & LABELS
    // =========================================================================

    private LinearLayout drawerBox(String title, Runnable onClose) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        box.setBackground(roundBox(0xFF111C1F, 0xFF2B454B, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = txt(title, 9.5f, CYAN);
        ht.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ht.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(ht);

        TextView close = txt("✕", 10, MUTE);
        close.setPadding(dp(this, 8), dp(this, 1), dp(this, 2), dp(this, 1));
        close.setOnClickListener(v -> onClose.run());
        head.addView(close);
        box.addView(head);
        return box;
    }

    private View cardDivider(int topDp, int bottomDp) {
        View div = new View(this);
        div.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(this, 1));
        dlp.topMargin = dp(this, topDp);
        dlp.bottomMargin = dp(this, bottomDp);
        div.setLayoutParams(dlp);
        return div;
    }

    private GradientDrawable roundBox(int fill, int stroke, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(this, radiusDp));
        bg.setStroke(dp(this, 1), stroke);
        return bg;
    }

    private TextView pillText(String s, float sp, int fg, int fill, int stroke) {
        TextView t = txt(s, sp, fg);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(this, 7), dp(this, 3), dp(this, 7), dp(this, 3));
        t.setBackground(roundBox(fill, stroke, 10));
        return t;
    }

    private TextView colText(String s, float sp, int color, float weight, int gravity) {
        TextView t = txt(s, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(gravity);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        return t;
    }

    private void bindPress(View view) {
        view.setClickable(true);
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static int ledgerVerdictColor(String verdict) {
        if (PersonaMatchEngine.VERDICT_RESTRICTED_LEAK.equals(verdict)
                || PersonaMatchEngine.VERDICT_TAINTED_IDENTITY.equals(verdict)) {
            return RED;
        }
        if (PersonaMatchEngine.VERDICT_DRIFT.equals(verdict)) {
            return AMBER;
        }
        if (PersonaMatchEngine.VERDICT_UNOBSERVED.equals(verdict)) {
            return MUTE;
        }
        return GREEN;
    }

    private static String ledgerVerdictTag(String verdict) {
        if (PersonaMatchEngine.VERDICT_RESTRICTED_LEAK.equals(verdict)) {
            return Lang.t("禁区", "leak", "制限");
        }
        if (PersonaMatchEngine.VERDICT_TAINTED_IDENTITY.equals(verdict)) {
            return Lang.t("污染", "taint", "汚染");
        }
        if (PersonaMatchEngine.VERDICT_DRIFT.equals(verdict)) {
            return Lang.t("漂移", "drift", "ズレ");
        }
        return "";
    }

    private static String formatDiagValue(CheckItem item, boolean isIdentity, boolean revealed) {
        if (!item.isRead()) {
            if (item.coverage() == CheckItem.Coverage.SERVER_ONLY) {
                return Lang.t("服务端", "server", "サーバ");
            }
            if (item.id().startsWith("target.su.") || "location.last_request".equals(item.id())
                    || item.id().startsWith("target.network_")
                    || "target.ipv6_spoof".equals(item.id())) {
                return Lang.t("0痕迹静默", "zero-trace", "静黙");
            }
            return "—";
        }
        String raw = item.value();
        if (raw == null || raw.isEmpty() || "not-local".equals(raw)) {
            return "—";
        }
        if (Privacy.hidden() && Privacy.sensitiveId(item.id())) {
            return Privacy.STARS;
        }
        if (isIdentity && !revealed) {
            return PersonaMatchEngine.maskIdentity(raw);
        }
        return raw;
    }

    private static String dimLabel(String dimId) {
        if (DiagnosticClassifier.DIM_STEALTH.equals(dimId)) {
            return Lang.t("隐蔽与残留", "Stealth", "隠蔽・残留");
        }
        if (DiagnosticClassifier.DIM_INTEGRITY.equals(dimId)) {
            return Lang.t("硬件与完整性", "Integrity", "整合性");
        }
        if (DiagnosticClassifier.DIM_NETLOC.equals(dimId)) {
            return Lang.t("网络与定位面", "Net & Loc", "通信・位置");
        }
        if (DiagnosticClassifier.DIM_IDENTITY.equals(dimId)) {
            return Lang.t("本地身份链", "Identity", "識別子");
        }
        if (DiagnosticClassifier.DIM_ACCOUNT.equals(dimId)) {
            return Lang.t("账号与会话", "Account", "アカウント");
        }
        return Lang.t("权限与传感器", "Perms", "権限・センサ");
    }

    private static String diagItemTitle(String id) {
        if (id == null) return "";
        switch (id) {
            // Stealth
            case "target.su.paths": return Lang.t("SU 路径自检", "SU path check", "SU パス検査");
            case "target.su.packages": return Lang.t("Root 应用包自检", "Root packages check", "Root パッケージ検査");
            case "target.su.properties": return Lang.t("调试属性自检", "Debug props check", "デバッグ属性検査");
            case "target.su.mounts": return Lang.t("系统挂载自检", "System mounts check", "マウント検査");
            case "system.build_tags": return Lang.t("系统签名标签", "Build tags", "ビルドタグ");
            // Integrity
            case "system.verified_boot": return Lang.t("启动链校验", "Verified boot", "ブート検証");
            case "system.selinux": return Lang.t("SELinux 状态", "SELinux mode", "SELinux 状態");
            case "system.strongbox": return Lang.t("硬件密钥库", "Hardware keystore", "ハードウェア鍵");
            case "system.play_integrity": return Lang.t("Play 完整性", "Play Integrity", "Play 整合性");
            case "system.android_id": return Lang.t("系统 ANDROID_ID", "System ANDROID_ID", "端末 ANDROID_ID");
            case "target.aid": return Lang.t("目标 ANDROID_ID", "Target ANDROID_ID", "対象 ANDROID_ID");
            // Network & Location
            case "location.mode": return Lang.t("系统定位开关", "Location mode", "位置情報モード");
            case "location.provider": return Lang.t("定位来源", "Location provider", "位置プロバイダ");
            case "location.mock": return Lang.t("模拟位置状态", "Mock location", "擬似位置");
            case "location.appops": return Lang.t("定位 AppOps", "Location AppOps", "位置 AppOps");
            case "location.last_request": return Lang.t("最近定位请求", "Last location request", "直近の位置取得");
            case "target.network_view": return Lang.t("目标网络视图", "Target network view", "対象ネット表示");
            case "target.network_addresses": return Lang.t("目标网卡地址", "Target interface IPs", "対象 IP 一覧");
            case "target.ipv6_spoof": return Lang.t("IPv6 伪装前缀", "IPv6 spoof prefix", "IPv6 偽装");
            // Identity chain
            case "sdk.statsig.stable": return Lang.t("Statsig 稳定 ID", "Statsig stable ID", "Statsig ID");
            case "sdk.statsig.locale": return Lang.t("Statsig 语言", "Statsig locale", "Statsig 言語");
            case "sdk.datadog.anonymous": return Lang.t("Datadog 匿名 ID", "Datadog anon ID", "Datadog ID");
            case "sdk.datadog.play_country": return Lang.t("Datadog 商店国别", "Datadog Play region", "Datadog 国");
            case "sdk.segment.anonymous": return Lang.t("Segment 匿名 ID", "Segment anon ID", "Segment 匿名 ID");
            case "sdk.segment.device": return Lang.t("Segment 设备 ID", "Segment device ID", "Segment 端末 ID");
            case "sdk.segment.user": return Lang.t("Segment 用户 ID", "Segment user ID", "Segment ユーザ ID");
            case "sdk.sift.installation": return Lang.t("Sift 安装 ID", "Sift install ID", "Sift 導入 ID");
            case "sdk.sift.fingerprint": return Lang.t("Sift 设备指纹", "Sift fingerprint", "Sift 指紋");
            case "sdk.sentry.installation": return Lang.t("Sentry 安装 ID", "Sentry install ID", "Sentry 導入 ID");
            case "sdk.sentry.device_tag": return Lang.t("Sentry 设备标签", "Sentry device tag", "Sentry 端末タグ");
            case "sdk.growthbook.unit": return Lang.t("GrowthBook 单元 ID", "GrowthBook unit", "GrowthBook ID");
            case "sdk.firebase.fid": return Lang.t("Firebase 安装 ID", "Firebase FID", "Firebase FID");
            case "sdk.bitdrift": return Lang.t("Bitdrift 遥测", "Bitdrift telemetry", "Bitdrift");
            // Account & Session
            case "account.play_country": return Lang.t("Play 商店国别", "Play Store country", "Play ストア国");
            case "account.trusted_device": return Lang.t("受信任设备状态", "Trusted device", "信頼済端末");
            case "account.local_handle": return Lang.t("本地账号句柄", "Local account handle", "ローカルアカウント");
            case "account.organization": return Lang.t("所属组织", "Organization", "組織");
            case "account.plan": return Lang.t("订阅方案", "Account plan", "プラン");
            case "account.subscription": return Lang.t("订阅状态", "Subscription", "購読状態");
            case "account.age_days": return Lang.t("账号账龄", "Account age", "アカウント日数");
            case "target.data.country_hint": return Lang.t("本地国家提示", "Local country hint", "国コードヒント");
            case "transport.cf_ray": return Lang.t("Cloudflare 边缘节点", "Cloudflare Ray", "CF Ray");
            case "transport.cflb": return Lang.t("Cloudflare 负载 Cookie", "Cloudflare LB cookie", "CF LB");
            case "transport.cfuvid": return Lang.t("Cloudflare 访客 Cookie", "Cloudflare visitor ID", "CF Visitor");
            case "transport.oai_sc": return Lang.t("OpenAI 会话 Cookie", "OpenAI session cookie", "OAI セッション");
            case "transport.oailb": return Lang.t("OpenAI 路由 Cookie", "OpenAI routing cookie", "OAI ルート");
            case "transport.session": return Lang.t("会话凭证", "Session token", "セッション");
            // Permissions & Process
            case "target.state": return Lang.t("目标进程状态", "Target process", "対象プロセス");
            case "target.version": return Lang.t("目标应用版本", "Target version", "対象バージョン");
            case "permission.location.fine": return Lang.t("精确位置权限", "Fine location", "精確な位置");
            case "permission.location.coarse": return Lang.t("粗略位置权限", "Coarse location", "おおよその位置");
            case "permission.location.background": return Lang.t("后台定位权限", "Background location", "バックグラウンド位置");
            case "permission.microphone": return Lang.t("麦克风权限", "Microphone", "マイク");
            case "permission.bluetooth_scan": return Lang.t("蓝牙扫描权限", "Bluetooth scan", "Bluetooth スキャン");
            case "permission.bluetooth_connect": return Lang.t("蓝牙连接权限", "Bluetooth connect", "Bluetooth 接続");
            case "permission.activity_recognition": return Lang.t("健身运动权限", "Activity recognition", "身体活動");
            case "permission.body_sensors": return Lang.t("身体传感器权限", "Body sensors", "体センサ");
            case "permission.calendar": return Lang.t("日历权限", "Calendar", "カレンダー");
            case "permission.health": return Lang.t("健康数据权限", "Health data", "ヘルスデータ");
            case "permission.screen_capture": return Lang.t("录屏权限", "Screen capture", "画面録画");
            case "permission.wifi": return Lang.t("附近 Wi-Fi 权限", "Nearby Wi-Fi", "付近の Wi-Fi");
            default: return id;
        }
    }

    private static String gateTitle(String gate) {
        if (BodyguardPolicy.GATE_IP_FLIP.equals(gate)) {
            return Lang.t("IP 保护", "IP Protection", "IP 保護");
        }
        if (BodyguardPolicy.GATE_TELEMETRY_KEY.equals(gate)) {
            return Lang.t("遥测字段", "Telemetry", "計測キー");
        }
        if (BodyguardPolicy.GATE_SYS_PROP.equals(gate)) {
            return Lang.t("系统属性", "SysProps", "システム属性");
        }
        if (BodyguardPolicy.GATE_SETTINGS_KEY.equals(gate)) {
            return Lang.t("系统设置", "Settings", "システム設定");
        }
        if (BodyguardPolicy.GATE_TRIPWIRE_API.equals(gate)) {
            return Lang.t("敏感接口", "Device APIs", "高危険 API");
        }
        if (BodyguardPolicy.GATE_DYNAMIC_CODE.equals(gate)) {
            return Lang.t("动态加载", "Code Load", "動的コード");
        }
        if (BodyguardPolicy.GATE_EGRESS_HOST.equals(gate)) {
            return Lang.t("外发域名", "Domains", "外部通信先");
        }
        return Lang.t("敏感文件", "Sys Files", "機密パス");
    }

    private static String gateScope(String gate) {
        if (BodyguardPolicy.GATE_IP_FLIP.equals(gate)) {
            return "Proxy drop / Restricted / Country flip";
        }
        if (BodyguardPolicy.GATE_TELEMETRY_KEY.equals(gate)) {
            return "Firelog event_metadata";
        }
        if (BodyguardPolicy.GATE_SYS_PROP.equals(gate)) {
            return "ro.ril / gsm / ro.build / ro.soc";
        }
        if (BodyguardPolicy.GATE_SETTINGS_KEY.equals(gate)) {
            return "Settings.Secure / Global / System";
        }
        if (BodyguardPolicy.GATE_TRIPWIRE_API.equals(gate)) {
            return "Telephony / Build / Location / PM / DRM";
        }
        if (BodyguardPolicy.GATE_DYNAMIC_CODE.equals(gate)) {
            return "DexClassLoader / InMemoryDex / exec";
        }
        if (BodyguardPolicy.GATE_EGRESS_HOST.equals(gate)) {
            return "DNS / URL / Socket allowlist";
        }
        return "/proc/self/* /sys/* su";
    }

    private static String formatTime(long ms) {
        if (ms <= 0L) {
            return "—";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
    }
}
