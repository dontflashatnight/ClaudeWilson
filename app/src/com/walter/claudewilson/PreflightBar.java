package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.walter.claudewilson.model.PreflightVerdict;

/**
 * Top Pre-flight Launch Readiness Bar ("起飞前核验").
 *
 * <p>Sits between the Masthead target switcher and Persona Space, surfacing a single
 * GO / CAUTION / BLOCKED verdict with a contextual 1-tap remediation action and an optional
 * inline expandable checklist of active blockers/warnings.
 */
final class PreflightBar extends UiSection {

    interface Actions {
        void stopTarget();
        void alignPersona();
        void pickRegion();
        void alignEgress();
        void probeNetwork();
    }

    private static volatile boolean expanded;

    private final Actions actions;

    PreflightBar(Context context, LinearLayout root, Actions actions) {
        super(context);
        this.root = root;
        this.actions = actions;
    }

    void render(PreflightVerdict verdict, String targetName, String regionLabel, boolean busy) {
        if (verdict == null) return;
        final boolean hasIssues = !verdict.issues.isEmpty();
        if (!hasIssues) {
            expanded = false;
        }

        int accent;
        int fill;
        int stroke;
        String headline;
        if (busy) {
            accent = AMBER;
            fill = 0x15E5A93B;
            stroke = 0x55E5A93B;
            headline = Lang.t("环境切换中…", "Switching…", "切替中…");
        } else if (verdict.level == PreflightVerdict.Level.BLOCKED) {
            accent = RED;
            fill = 0x1AE55353;
            stroke = 0x73E55353;
            String prefix = verdict.targetRunning
                    ? Lang.t("需先停止 · ", "Stop Required · ", "停止が必要 · ")
                    : Lang.t("禁止启动 · ", "Blocked · ", "起動不可 · ");
            headline = prefix
                    + verdict.blockerCount + Lang.t(" 项阻断", " blocker", " 件");
        } else if (verdict.level == PreflightVerdict.Level.CAUTION) {
            accent = AMBER;
            fill = 0x16E5A93B;
            stroke = 0x66E5A93B;
            headline = verdict.warningCount + Lang.t(" 项风险 · 可核查",
                    " caution", " 件の注意");
        } else {
            accent = GREEN;
            fill = 0x1434D399;
            stroke = 0x6634D399;
            String state = verdict.targetRunning
                    ? Lang.t("安全运行中", "Running", "稼働中")
                    : Lang.t("可安全启动", "Ready", "起動可能");
            headline = state
                    + (regionLabel != null && !regionLabel.isEmpty() ? " · " + regionLabel : "");
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(this, 12), dp(this, 9), dp(this, 12), dp(this, 9));
        container.setBackground(box(fill, stroke, 14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(this, 10);
        container.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView headTv = txt(headline, 11.5f, accent);
        headTv.setTypeface(Typeface.DEFAULT_BOLD);
        headTv.setSingleLine(true);
        headTv.setEllipsize(TextUtils.TruncateAt.END);
        headTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(headTv);

        if (!busy && verdict.primaryAction != PreflightVerdict.PrimaryAction.NONE) {
            String actLabel = actionLabel(verdict.primaryAction);
            int actFg = verdict.level == PreflightVerdict.Level.BLOCKED ? RED : CYAN;
            int actFill = verdict.level == PreflightVerdict.Level.BLOCKED ? 0x26E55353 : 0x1F2EC4B6;
            int actStroke = verdict.level == PreflightVerdict.Level.BLOCKED ? 0x8CE55353 : 0x662EC4B6;
            TextView actBtn = txt(actLabel, 10, actFg);
            actBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            actBtn.setSingleLine(true);
            actBtn.setPadding(dp(this, 9), dp(this, 4), dp(this, 9), dp(this, 4));
            actBtn.setBackground(box(actFill, actStroke, 12));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
            alp.leftMargin = dp(this, 8);
            actBtn.setLayoutParams(alp);
            bindPress(actBtn);
            actBtn.setOnClickListener(v -> runAction(verdict.primaryAction));
            top.addView(actBtn);
        }

        container.addView(top);

        if (!busy && hasIssues && expanded) {
            View div = new View(this);
            div.setBackgroundColor(0x551E3136);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 1);
            vlp.topMargin = dp(this, 8);
            vlp.bottomMargin = dp(this, 6);
            div.setLayoutParams(vlp);
            container.addView(div);

            for (PreflightVerdict.Issue issue : verdict.issues) {
                boolean isBlocker = issue.severity == PreflightVerdict.Severity.BLOCKER;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(this, 3), 0, dp(this, 3));

                TextView badge = txt(isBlocker
                        ? Lang.t("阻断", "BLOCK", "不可")
                        : Lang.t("风险", "WARN", "注意"), 9, isBlocker ? RED : AMBER);
                badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                badge.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                badge.setBackground(box(isBlocker ? 0x24E55353 : 0x20E5A93B,
                        isBlocker ? 0x73E55353 : 0x66E5A93B, 8));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.rightMargin = dp(this, 8);
                badge.setLayoutParams(blp);
                row.addView(badge);

                TextView desc = txt(issueText(issue, targetName), 10.5f, FG);
                desc.setTypeface(Typeface.MONOSPACE);
                desc.setSingleLine(true);
                desc.setEllipsize(TextUtils.TruncateAt.END);
                desc.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                row.addView(desc);

                container.addView(row);
            }
        }

        if (!busy && hasIssues) {
            bindPress(top);
            top.setOnClickListener(v -> {
                expanded = !expanded;
                if (getBaseContext() instanceof Dashboard) {
                    ((Dashboard) getBaseContext()).refreshPreflight();
                }
            });
        }

        root.addView(container);
    }

    private void runAction(PreflightVerdict.PrimaryAction action) {
        if (actions == null) return;
        switch (action) {
            case STOP_TARGET: actions.stopTarget(); break;
            case ALIGN_PERSONA: actions.alignPersona(); break;
            case PICK_REGION: actions.pickRegion(); break;
            case ALIGN_EGRESS: actions.alignEgress(); break;
            case PROBE_NETWORK: actions.probeNetwork(); break;
            default: break;
        }
    }

    private static String actionLabel(PreflightVerdict.PrimaryAction action) {
        switch (action) {
            case STOP_TARGET: return Lang.t("停止目标", "Stop App", "停止");
            case ALIGN_PERSONA: return Lang.t("对齐人设", "Align", "同期");
            case PICK_REGION: return Lang.t("选地区", "Region", "地域");
            case ALIGN_EGRESS: return Lang.t("对齐出口", "Align Exit", "出口同期");
            case PROBE_NETWORK: return Lang.t("测出口", "Probe", "測定");
            default: return "";
        }
    }

    private static String issueText(PreflightVerdict.Issue issue, String targetName) {
        String tgt = targetName == null || targetName.isEmpty() ? "Target" : targetName;
        switch (issue.code) {
            case TARGET_RUNNING:
                return tgt + Lang.t(" 进程存活 · 需先停止", " running · force-stop first", " 実行中");
            case NATIVE_RESTRICTED_SIM:
                return Lang.t("原机态且物理卡属禁区", "Native mode with restricted SIM", "制限国SIMのまま");
            case EGRESS_RESTRICTED:
                return Lang.t("出口 IP 位于禁区 ", "Exit IP restricted ", "制限国出口 ") + issue.detail;
            case PERSONA_DRIFT:
                return Lang.t("人设存在 ", "Persona drift: ") + issue.detail + Lang.t(" 项偏差", "");
            case EGRESS_MISMATCH:
                return Lang.t("出口与人设地区不一致 ", "Exit/Region mismatch ") + issue.detail;
            case NETWORK_LEAK:
                return Lang.t("存在 DNS / IPv6 / 代理泄露", "DNS / IPv6 / Proxy leak", "通信リーク");
            case DATACENTER_IP:
                return Lang.t("当前出口为机房 IP", "Datacenter ASN exit IP", "DC回線IP");
            case STORE_MISMATCH:
                return Lang.t("Play 商店国别不一致 ", "Play store mismatch ") + issue.detail;
            case EGRESS_UNPROBED:
                return Lang.t("尚未探测当前网络出口", "Network exit not probed", "出口未測定");
            default:
                return issue.code.name();
        }
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(this, radiusDp));
        bg.setStroke(dp(this, 1), stroke);
        return bg;
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
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
