package com.walter.claudewilson;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.walter.claudewilson.model.IpGuardPolicy;

import java.util.Locale;

/**
 * Dark-slate BottomSheet for Bodyguard Gate 8 ("IP 保护"), matching {@link PersonaEditSheets}
 * visual primitives with zero explanatory prose.
 */
final class IpGuardSheet {

    private static final int BG = UiSection.BG;
    private static final int CARD = UiSection.CARD;
    private static final int CARD2 = UiSection.CARD2;
    private static final int STROKE = UiSection.STROKE;
    private static final int STROKE2 = 0xFF2B454B;
    private static final int FG = UiSection.FG;
    private static final int DIM = UiSection.DIM;
    private static final int MUTE = UiSection.MUTE;
    private static final int GREEN = UiSection.GREEN;
    private static final int AMBER = UiSection.AMBER;
    private static final int RED = UiSection.RED;
    private static final int ACCENT = UiSection.ACCENT;
    private static final int SAND = UiSection.SAND;

    interface Callback {
        void onSave(IpGuardPolicy.Config config);
    }

    private IpGuardSheet() {
    }

    static void show(final Context ctx, final String expectedIso, final String baselineIp,
                     final boolean rootReady, final IpGuardPolicy.Config current,
                     final Callback callback) {
        if (ctx == null) {
            return;
        }
        final IpGuardPolicy.Config init = current == null ? IpGuardPolicy.Config.defaults() : current;
        final boolean[] killDrop = new boolean[]{init.killOnDrop};
        final boolean[] killRest = new boolean[]{init.killOnRestricted};
        final String[] drift = new String[]{init.driftPolicy};
        final boolean[] pollMin = new boolean[]{init.pollEveryMin};

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 20));

        // Drag handle
        View handle = new View(ctx);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(STROKE2);
        hbg.setCornerRadius(dp(ctx, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 12);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Header: Title + Target region pill
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(ctx, 12);
        head.setLayoutParams(headLp);

        TextView title = txt(ctx, Lang.t("IP 保护", "IP Protection", "IP 保護"), 15, FG, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);

        String isoUp = IpGuardPolicy.normalizeIso(expectedIso);
        String targetText = isoUp.isEmpty()
                ? Lang.t("未选地区", "No region", "地域未選択")
                : Lang.t("目标 ", "Target ", "対象 ") + regionDisplay(isoUp);
        TextView targetPill = pill(ctx, targetText, 10.5f,
                isoUp.isEmpty() ? DIM : GREEN,
                isoUp.isEmpty() ? CARD2 : 0x1634D399,
                isoUp.isEmpty() ? STROKE : 0x5934D399);
        head.addView(targetPill);
        sheet.addView(head);

        // Dynamic body container so switch / segment state updates atomically
        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(body);

        final Runnable[] renderBody = new Runnable[1];
        renderBody[0] = () -> {
            body.removeAllViews();

            // Card 1: 3 trigger rows
            LinearLayout card1 = groupCard(ctx, 11);

            // Row 1: Proxy drop / local direct bypass
            card1.addView(switchRow(ctx, RED,
                    Lang.t("代理断开 / 走本地网", "Proxy Drops / Direct", "プロキシ切断 / 直接通信"),
                    "", killDrop[0], true, () -> {
                        killDrop[0] = !killDrop[0];
                        renderBody[0].run();
                    }));
            card1.addView(hairline(ctx));

            // Row 2: Restricted region
            card1.addView(switchRow(ctx, RED,
                    Lang.t("IP 变到禁区", "Blocked Region", "制限地域への変化"),
                    "CN/HK/RU…", killRest[0], true, () -> {
                        killRest[0] = !killRest[0];
                        renderBody[0].run();
                    }));
            card1.addView(hairline(ctx));

            // Row 3: Other country (3-way segmented control)
            LinearLayout driftRow = new LinearLayout(ctx);
            driftRow.setOrientation(LinearLayout.HORIZONTAL);
            driftRow.setGravity(Gravity.CENTER_VERTICAL);
            driftRow.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

            driftRow.addView(dotView(ctx, AMBER));
            TextView dLbl = txt(ctx,
                    Lang.t("IP 换了其他国家", "Other Country", "他国への変化"),
                    12.5f, FG, true);
            dLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            driftRow.addView(dLbl);

            LinearLayout seg = new LinearLayout(ctx);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
            seg.setBackground(box(ctx, BG, STROKE, 9));
            seg.addView(segItem(ctx, Lang.t("不管", "Ignore", "無視"),
                    IpGuardPolicy.DRIFT_OFF.equals(drift[0]), CARD2, FG, () -> {
                        drift[0] = IpGuardPolicy.DRIFT_OFF;
                        renderBody[0].run();
                    }));
            seg.addView(segItem(ctx, Lang.t("提醒", "Alert", "通知"),
                    IpGuardPolicy.DRIFT_WARN.equals(drift[0]), 0x33E5A93B, AMBER, () -> {
                        drift[0] = IpGuardPolicy.DRIFT_WARN;
                        renderBody[0].run();
                    }));
            seg.addView(segItem(ctx, Lang.t("拦住", "Block", "遮断"),
                    IpGuardPolicy.DRIFT_KILL.equals(drift[0]), 0x38E55353, 0xFFFCA5A5, () -> {
                        drift[0] = IpGuardPolicy.DRIFT_KILL;
                        renderBody[0].run();
                    }));
            driftRow.addView(seg);
            card1.addView(driftRow);
            body.addView(card1);

            // Card 2: Periodic 1-min IP check + pinned baseline IP display
            boolean needCountry = killRest[0] || !IpGuardPolicy.DRIFT_OFF.equals(drift[0]);
            LinearLayout card2 = groupCard(ctx, 11);
            card2.addView(switchRow(ctx, 0,
                    Lang.t("每分钟核对 IP", "Check IP Every Minute", "毎分 IP を確認"),
                    "", pollMin[0] && needCountry, needCountry, () -> {
                        if (!needCountry) return;
                        pollMin[0] = !pollMin[0];
                        renderBody[0].run();
                    }));
            card2.addView(hairline(ctx));
            LinearLayout baseRow = new LinearLayout(ctx);
            baseRow.setOrientation(LinearLayout.HORIZONTAL);
            baseRow.setGravity(Gravity.CENTER_VERTICAL);
            baseRow.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9));
            TextView bLbl = txt(ctx, Lang.t("基线 IP", "Baseline IP", "基準 IP"), 11.5f, DIM, false);
            bLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            baseRow.addView(bLbl);
            boolean hasBase = baselineIp != null && !baselineIp.trim().isEmpty();
            TextView bVal = txt(ctx,
                    hasBase ? Privacy.mask(baselineIp.trim())
                            : Lang.t("首次使用时记录", "Set on first use", "初回使用時に記録"),
                    11, hasBase ? FG : MUTE, hasBase);
            if (hasBase) {
                bVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            }
            baseRow.addView(bVal);
            card2.addView(baseRow);
            body.addView(card2);

            // Strip 3: Enforcement capability pills
            LinearLayout capStrip = new LinearLayout(ctx);
            capStrip.setOrientation(LinearLayout.HORIZONTAL);
            capStrip.setGravity(Gravity.CENTER_VERTICAL);
            capStrip.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9));
            capStrip.setBackground(box(ctx, BG, STROKE, 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(ctx, 13);
            capStrip.setLayoutParams(clp);

            TextView capLbl = txt(ctx, Lang.t("拦住方式", "Action", "遮断方法"), 10.5f, MUTE, false);
            capLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            capStrip.addView(capLbl);

            if (rootReady) {
                capStrip.addView(capBadge(ctx, Lang.t("断网", "Cut Net", "通信遮断"), true));
                capStrip.addView(capBadge(ctx, Lang.t("关闭应用", "Close App", "停止"), true));
                capStrip.addView(capBadge(ctx, Lang.t("禁止重开", "Stay Locked", "再起動ロック"), true));
            } else {
                capStrip.addView(capBadge(ctx,
                        Lang.t("仅提醒（无 Root）", "Alert Only (No Root)", "通知のみ (Rootなし)"), false));
            }
            body.addView(capStrip);
        };
        renderBody[0].run();

        // Primary Done button
        TextView done = txt(ctx, Lang.t("完成", "Done", "完了"), 13, BG, true);
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, dp(ctx, 11), 0, dp(ctx, 11));
        done.setBackground(box(ctx, ACCENT, ACCENT, 12));
        bindPress(done);
        done.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) {
                callback.onSave(new IpGuardPolicy.Config(
                        killDrop[0], killRest[0], drift[0], pollMin[0]));
            }
        });
        sheet.addView(done);

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF0F1A1D);
            float r = dp(ctx, 22);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            bg.setStroke(dp(ctx, 1), STROKE2);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.72f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    /**
     * Confirmation sheet shown on Unlock whenever the verified live IP differs from the pinned
     * first-use baseline IP, letting the user explicitly adopt the new IP or keep the original.
     */
    static void showBaselineSwitchConfirm(final Context ctx,
                                          final String oldIp, final String oldIso,
                                          final String newIp, final String newIso,
                                          final Runnable onAdoptNewBaseline,
                                          final Runnable onKeepOriginal) {
        if (ctx == null) {
            return;
        }
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final boolean[] handled = new boolean[]{false};

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 20));

        // Drag handle
        View handle = new View(ctx);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(STROKE2);
        hbg.setCornerRadius(dp(ctx, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 12);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Title
        TextView title = txt(ctx,
                Lang.t("切换基线 IP？", "Switch Baseline IP?", "基準 IP を変更しますか？"),
                15, FG, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = dp(ctx, 12);
        title.setLayoutParams(tlp);
        sheet.addView(title);

        // Comparison card: Original baseline vs Live IP (layout explains itself, zero extra prose)
        LinearLayout card = groupCard(ctx, 14);
        card.addView(ipCompareRow(ctx,
                Lang.t("原基线", "Baseline", "元の基準"),
                oldIp, oldIso, DIM, DIM, false));
        card.addView(hairline(ctx));
        card.addView(ipCompareRow(ctx,
                Lang.t("当前 IP", "Current", "現在の IP"),
                newIp, newIso, GREEN, FG, true));
        sheet.addView(card);

        // Primary CTA: Adopt new baseline & unlock
        TextView adoptBtn = txt(ctx,
                Lang.t("设为新基线并解锁", "Use New Baseline & Unlock", "新しい基準に設定して解除"),
                13, BG, true);
        adoptBtn.setGravity(Gravity.CENTER);
        adoptBtn.setPadding(0, dp(ctx, 11), 0, dp(ctx, 11));
        adoptBtn.setBackground(box(ctx, ACCENT, ACCENT, 12));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.bottomMargin = dp(ctx, 8);
        adoptBtn.setLayoutParams(alp);
        bindPress(adoptBtn);
        adoptBtn.setOnClickListener(v -> {
            handled[0] = true;
            dialog.dismiss();
            if (onAdoptNewBaseline != null) onAdoptNewBaseline.run();
        });
        sheet.addView(adoptBtn);

        // Secondary action: Keep original baseline (stay locked until reverted)
        TextView keepBtn = txt(ctx,
                Lang.t("保持原基线", "Keep Original Baseline", "元の基準を維持"),
                12.5f, DIM, true);
        keepBtn.setGravity(Gravity.CENTER);
        keepBtn.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        keepBtn.setBackground(box(ctx, CARD2, STROKE, 12));
        bindPress(keepBtn);
        keepBtn.setOnClickListener(v -> {
            handled[0] = true;
            dialog.dismiss();
            if (onKeepOriginal != null) onKeepOriginal.run();
        });
        sheet.addView(keepBtn);

        dialog.setOnCancelListener(d -> {
            if (!handled[0]) {
                handled[0] = true;
                if (onKeepOriginal != null) onKeepOriginal.run();
            }
        });
        dialog.setOnDismissListener(d -> {
            if (!handled[0]) {
                handled[0] = true;
                if (onKeepOriginal != null) onKeepOriginal.run();
            }
        });

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF0F1A1D);
            float r = dp(ctx, 22);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            bg.setStroke(dp(ctx, 1), STROKE2);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.72f);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static View ipCompareRow(Context ctx, String label, String ip, String iso,
                                     int labelColor, int ipColor, boolean emphasize) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11));

        TextView lbl = txt(ctx, label, 11.5f, labelColor, emphasize);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(lbl);

        String masked = ip == null || ip.isEmpty() ? "—" : Privacy.mask(ip.trim());
        TextView val = txt(ctx, masked, emphasize ? 12.5f : 11.5f, ipColor, emphasize);
        val.setTypeface(Typeface.MONOSPACE, emphasize ? Typeface.BOLD : Typeface.NORMAL);
        row.addView(val);

        String isoUp = IpGuardPolicy.normalizeIso(iso);
        if (!isoUp.isEmpty()) {
            TextView tag = pill(ctx, isoUp, 9.5f,
                    emphasize ? GREEN : DIM,
                    emphasize ? 0x1634D399 : CARD,
                    emphasize ? 0x5934D399 : STROKE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(ctx, 8);
            tag.setLayoutParams(lp);
            row.addView(tag);
        }
        return row;
    }

    static String regionDisplay(String iso) {
        String u = IpGuardPolicy.normalizeIso(iso);
        if (u.isEmpty()) {
            return "";
        }
        String name = new Locale("", u).getDisplayCountry();
        if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(u)) {
            return name + " (" + u + ")";
        }
        return u;
    }

    private static LinearLayout groupCard(Context ctx, int bottomMarginDp) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(ctx, CARD, STROKE, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(ctx, bottomMarginDp);
        card.setLayoutParams(lp);
        return card;
    }

    private static View switchRow(Context ctx, int dotColor, String label, String subTag,
                                  boolean on, boolean enabled, Runnable onToggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11));
        row.setAlpha(enabled ? 1f : 0.42f);

        if (dotColor != 0) {
            row.addView(dotView(ctx, dotColor));
        }

        LinearLayout labelWrap = new LinearLayout(ctx);
        labelWrap.setOrientation(LinearLayout.HORIZONTAL);
        labelWrap.setGravity(Gravity.CENTER_VERTICAL);
        labelWrap.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tv = txt(ctx, label, 12.5f, FG, true);
        labelWrap.addView(tv);
        if (subTag != null && !subTag.isEmpty()) {
            TextView st = txt(ctx, "  " + subTag, 10, MUTE, false);
            st.setTypeface(Typeface.MONOSPACE);
            labelWrap.addView(st);
        }
        row.addView(labelWrap);
        row.addView(pillSwitch(ctx, on));

        if (enabled && onToggle != null) {
            bindPress(row);
            row.setOnClickListener(v -> onToggle.run());
        }
        return row;
    }

    private static View pillSwitch(Context ctx, boolean on) {
        LinearLayout track = new LinearLayout(ctx);
        track.setGravity(on ? (Gravity.END | Gravity.CENTER_VERTICAL)
                : (Gravity.START | Gravity.CENTER_VERTICAL));
        track.setPadding(dp(ctx, 2.5f), dp(ctx, 2.5f), dp(ctx, 2.5f), dp(ctx, 2.5f));
        track.setBackground(box(ctx, on ? 0x3D34D399 : BG, on ? GREEN : STROKE2, 12));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 23));
        track.setLayoutParams(tlp);

        View thumb = new View(ctx);
        GradientDrawable tb = new GradientDrawable();
        tb.setShape(GradientDrawable.OVAL);
        tb.setColor(on ? GREEN : DIM);
        thumb.setBackground(tb);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 17), dp(ctx, 17)));
        track.addView(thumb);
        return track;
    }

    private static View segItem(Context ctx, String label, boolean active,
                                int activeFill, int activeText, Runnable onTap) {
        TextView tv = txt(ctx, label, 11, active ? activeText : DIM, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5));
        if (active) {
            tv.setBackground(box(ctx, activeFill, Color.TRANSPARENT, 7));
        }
        bindPress(tv);
        tv.setOnClickListener(v -> onTap.run());
        return tv;
    }

    private static View dotView(Context ctx, int color) {
        View d = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        d.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6));
        lp.rightMargin = dp(ctx, 8);
        d.setLayoutParams(lp);
        return d;
    }

    private static View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0xA61E3136);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    private static TextView capBadge(Context ctx, String text, boolean active) {
        TextView tv = pill(ctx, text, 10,
                active ? SAND : MUTE,
                active ? 0x162EC4B6 : Color.TRANSPARENT,
                active ? 0x592EC4B6 : STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(ctx, 5);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView pill(Context ctx, String text, float sp,
                                 int textColor, int fill, int stroke) {
        TextView tv = txt(ctx, text, sp, textColor, true);
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tv.setBackground(box(ctx, fill, stroke, 999));
        return tv;
    }

    private static TextView txt(Context ctx, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable box(Context ctx, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(ctx, radiusDp));
        if (stroke != Color.TRANSPARENT) {
            g.setStroke(dp(ctx, 1), stroke);
        }
        return g;
    }

    private static void bindPress(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.75f);
            } else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setAlpha(1f);
            }
            return false;
        });
    }

    private static int dp(Context ctx, float v) {
        return UiSection.dp(ctx, v);
    }
}
