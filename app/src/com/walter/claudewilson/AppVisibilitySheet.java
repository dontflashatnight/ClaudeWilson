package com.walter.claudewilson;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.walter.claudewilson.model.AppVisibilityPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HMA-grade dark obsidian-terracotta BottomSheet for configuring per-space target package
 * visibility ({@code allowlist / denylist / default}, {@code excludeSystemApps}, {@code strictHide})
 * and HMA Preset Settings (8 live-scanned app categories + 3 system feature shields:
 * LSPosed/Xposed, Root Managers, Shizuku Modules, Suspicious Apps, Accessibility Apps,
 * Detectors, Custom ROM Apps, Input Methods, Developer Options, Accessibility Feature, IME Feature).
 */
final class AppVisibilitySheet {
    private static final int BG = UiSection.BG;
    private static final int CARD = UiSection.CARD;
    private static final int CARD2 = UiSection.CARD2;
    private static final int STROKE = UiSection.STROKE;
    private static final int STROKE2 = 0xFF2B454B;
    private static final int FG = UiSection.FG;
    private static final int DIM = UiSection.DIM;
    private static final int MUTE = UiSection.MUTE;
    private static final int ACCENT = UiSection.ACCENT;
    private static final int SAND = UiSection.SAND;
    private static final int AMBER = UiSection.AMBER;
    private static final int RED = UiSection.RED;

    interface SaveCallback {
        void onSave(String mode,
                    Set<String> selectedPackages,
                    Set<String> extraHiddenPackages,
                    boolean excludeSystemApps,
                    boolean strictHide,
                    boolean hideDevOptions,
                    boolean hideA11yFeature,
                    boolean hideImeFeature,
                    Set<String> checkedPresetGroups);
    }

    private static final class AppItem {
        final String pkg;
        final String label;
        final String lowerLabel;
        final String lowerPkg;
        final boolean system;
        final boolean essential;
        final boolean forcedHidden;
        final int category;
        Drawable icon;

        AppItem(String pkg, String label, boolean system, boolean essential,
                boolean forcedHidden, int category, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.lowerLabel = label.toLowerCase(Locale.ROOT);
            this.lowerPkg = pkg.toLowerCase(Locale.ROOT);
            this.system = system;
            this.essential = essential;
            this.forcedHidden = forcedHidden;
            this.category = category;
            this.icon = icon;
        }

        boolean sensitive() {
            return category != AppVisibilityPolicy.CAT_NONE;
        }
    }

    private AppVisibilitySheet() {
    }

    static void show(final Context ctx,
                     final String targetName,
                     final String targetPkg,
                     final String initialMode,
                     final Set<String> initialSelected,
                     final Set<String> initialExtraHidden,
                     final boolean initialExcludeSystem,
                     final boolean initialStrictHide,
                     final boolean initialHideDev,
                     final boolean initialHideA11y,
                     final boolean initialHideIme,
                     final Set<String> initialPresetGroups,
                     final SaveCallback onSave) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final String[] mode = {AppVisibilityPolicy.normalizeMode(initialMode)};
        final boolean[] excludeSys = {initialExcludeSystem};
        final boolean[] strictHide = {initialStrictHide};
        final boolean[] hideDev = {initialHideDev};
        final boolean[] hideA11y = {initialHideA11y};
        final boolean[] hideIme = {initialHideIme};
        final Set<String> selected = new LinkedHashSet<String>();
        if (initialSelected != null) selected.addAll(initialSelected);
        final Set<String> extraHidden = new LinkedHashSet<String>();
        if (initialExtraHidden != null) extraHidden.addAll(initialExtraHidden);
        final Set<String> checkedGroups = new LinkedHashSet<String>();
        if (initialPresetGroups != null) checkedGroups.addAll(initialPresetGroups);

        final String[] filter = {"USER"}; // USER | RISK | SELECTED | SYSTEM
        final String[] query = {""};
        final List<AppItem> allApps = new ArrayList<AppItem>();
        final List<AppItem> visibleApps = new ArrayList<AppItem>();
        final boolean[] loaded = {false};

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 18));

        // Grab handle
        View handle = new View(ctx);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(STROKE2);
        hbg.setCornerRadius(dp(ctx, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 11);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Header: title + [设置预设] pill + [✕]
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(ctx, 10);
        head.setLayoutParams(headLp);
        TextView title = txt(ctx,
                Lang.t("应用列表 · ", "App Visibility · ", "アプリ一覧 · ") + targetName,
                15.5f, FG, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);

        final TextView presetBtn = txt(ctx, Lang.t("设置预设", "Presets", "プリセット"), 10f, SAND, true);
        presetBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        presetBtn.setGravity(Gravity.CENTER);
        presetBtn.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        presetBtn.setBackground(box(ctx, 0x1A7DD3D8, 0x557DD3D8, 12));
        LinearLayout.LayoutParams pmlp = new LinearLayout.LayoutParams(-2, -2);
        pmlp.rightMargin = dp(ctx, 7);
        presetBtn.setLayoutParams(pmlp);
        bindPress(presetBtn);
        head.addView(presetBtn);

        TextView close = txt(ctx, "✕", 14, DIM, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(ctx, CARD2, STROKE, 14));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        bindPress(close);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head);

        // 1. Mode segmented control: 白名单 | 黑名单 | 默认
        final LinearLayout modeSeg = new LinearLayout(ctx);
        modeSeg.setOrientation(LinearLayout.HORIZONTAL);
        modeSeg.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
        modeSeg.setBackground(box(ctx, BG, STROKE, 12));
        LinearLayout.LayoutParams mslp = new LinearLayout.LayoutParams(-1, -2);
        mslp.bottomMargin = dp(ctx, 8);
        modeSeg.setLayoutParams(mslp);
        sheet.addView(modeSeg);

        // 2. HMA policy toggles row (Exclude System Apps + Strict Side-Channel Shield)
        final LinearLayout toggleRow = new LinearLayout(ctx);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(-1, -2);
        tglp.bottomMargin = dp(ctx, 8);
        sheet.addView(toggleRow, tglp);

        // 3. Picker body (hidden when mode == DEFAULT)
        final LinearLayout pickerBody = new LinearLayout(ctx);
        pickerBody.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(pickerBody);

        // Search input
        EditText search = new EditText(ctx);
        search.setHint(Lang.t("搜索应用名或包名…", "Search app or package…", "アプリ・パッケージ検索…"));
        search.setHintTextColor(MUTE);
        search.setTextColor(FG);
        search.setTextSize(11.5f);
        search.setTypeface(Typeface.MONOSPACE);
        search.setSingleLine(true);
        search.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        search.setBackground(box(ctx, BG, STROKE, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(ctx, 7);
        search.setLayoutParams(slp);
        pickerBody.addView(search);

        // Filter chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(-1, -2);
        cslp.bottomMargin = dp(ctx, 7);
        pickerBody.addView(chipScroll, cslp);

        // Recycled ListView
        final ListView listView = new ListView(ctx);
        listView.setDivider(null);
        listView.setDividerHeight(dp(ctx, 5));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int listH = Math.min(dp(ctx, 325),
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.40f));
        listView.setLayoutParams(new LinearLayout.LayoutParams(-1, listH));
        pickerBody.addView(listView);

        final TextView loadingTv = txt(ctx,
                Lang.t("读取已装应用…", "Loading installed apps…", "アプリ一覧を読込中…"),
                11, DIM, false);
        loadingTv.setTypeface(Typeface.MONOSPACE);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, dp(ctx, 26), 0, dp(ctx, 26));
        pickerBody.addView(loadingTv);

        // Compact live summary bar
        final TextView summaryTv = txt(ctx, "", 9.5f, SAND, false);
        summaryTv.setTypeface(Typeface.MONOSPACE);
        summaryTv.setSingleLine(true);
        summaryTv.setEllipsize(TextUtils.TruncateAt.END);
        summaryTv.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        summaryTv.setBackground(box(ctx, 0x162EC4B6, 0x4D2EC4B6, 11));
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(-1, -2);
        sumLp.topMargin = dp(ctx, 8);
        sumLp.bottomMargin = dp(ctx, 10);
        sheet.addView(summaryTv, sumLp);

        // Footer
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = txt(ctx, Lang.t("取消", "Cancel", "キャンセル"), 12, DIM, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        cancel.setBackground(box(ctx, CARD2, STROKE, 12));
        bindPress(cancel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.rightMargin = dp(ctx, 8);
        foot.addView(cancel, clp);

        TextView saveBtn = txt(ctx, Lang.t("保存并应用", "Save", "保存"), 12, 0xFF041918, true);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        saveBtn.setBackground(box(ctx, ACCENT, ACCENT, 12));
        bindPress(saveBtn);
        saveBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (onSave != null) {
                onSave.onSave(
                        mode[0],
                        new LinkedHashSet<String>(selected),
                        new LinkedHashSet<String>(extraHidden),
                        excludeSys[0],
                        strictHide[0],
                        hideDev[0],
                        hideA11y[0],
                        hideIme[0],
                        new LinkedHashSet<String>(checkedGroups));
            }
        });
        foot.addView(saveBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.addView(foot);

        final Runnable[] refreshUi = new Runnable[1];

        // Open HMA "设置预设" sheet (8 live-scanned app categories + 3 system feature shields)
        presetBtn.setOnClickListener(v -> showHmaPresetSettingsSheet(
                ctx,
                targetPkg,
                checkedGroups,
                hideDev[0],
                hideA11y[0],
                hideIme[0],
                (newGroups, matchedToHide, matchedSystemToHide, newHideDev, newHideA11y, newHideIme) -> {
                    checkedGroups.clear();
                    checkedGroups.addAll(newGroups);
                    hideDev[0] = newHideDev;
                    hideA11y[0] = newHideA11y;
                    hideIme[0] = newHideIme;

                    extraHidden.clear();
                    if (AppVisibilityPolicy.MODE_ALLOWLIST.equals(mode[0])) {
                        // Allowlist: remove preset-hidden packages from allowlist, and put
                        // matched system packages (e.g. Custom ROM / system A11y / IME) into
                        // extraHidden so they stay hidden even when Exclude System is ON.
                        selected.removeAll(matchedToHide);
                        extraHidden.addAll(matchedSystemToHide);
                    } else if (AppVisibilityPolicy.MODE_DENYLIST.equals(mode[0])) {
                        // Denylist: add all matched non-essential packages directly to hidden list.
                        selected.addAll(matchedToHide);
                        extraHidden.addAll(matchedSystemToHide);
                    } else {
                        // Default mode: enforce all preset-selected packages via extraHidden.
                        extraHidden.addAll(matchedToHide);
                    }
                    refreshUi[0].run();
                }));

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visibleApps.size(); }
            @Override public Object getItem(int pos) { return visibleApps.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                final AppItem item = visibleApps.get(pos);
                LinearLayout row;
                ImageView iconView;
                TextView nameTv;
                TextView pkgTv;
                TextView badgeTv;
                TextView checkTv;
                if (convertView instanceof LinearLayout && convertView.getTag() instanceof Object[]) {
                    row = (LinearLayout) convertView;
                    Object[] h = (Object[]) row.getTag();
                    iconView = (ImageView) h[0];
                    nameTv = (TextView) h[1];
                    pkgTv = (TextView) h[2];
                    badgeTv = (TextView) h[3];
                    checkTv = (TextView) h[4];
                } else {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));

                    iconView = new ImageView(ctx);
                    int isz = dp(ctx, 30);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
                    ilp.rightMargin = dp(ctx, 10);
                    iconView.setLayoutParams(ilp);
                    row.addView(iconView);

                    LinearLayout mid = new LinearLayout(ctx);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    nameTv = txt(ctx, "", 11.5f, FG, true);
                    nameTv.setSingleLine(true);
                    nameTv.setEllipsize(TextUtils.TruncateAt.END);
                    mid.addView(nameTv);
                    pkgTv = txt(ctx, "", 9, DIM, false);
                    pkgTv.setTypeface(Typeface.MONOSPACE);
                    pkgTv.setSingleLine(true);
                    pkgTv.setEllipsize(TextUtils.TruncateAt.END);
                    mid.addView(pkgTv);
                    row.addView(mid);

                    badgeTv = txt(ctx, "", 8.5f, AMBER, true);
                    badgeTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    badgeTv.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                    blp.leftMargin = dp(ctx, 6);
                    badgeTv.setLayoutParams(blp);
                    row.addView(badgeTv);

                    checkTv = txt(ctx, "", 9, ACCENT, true);
                    checkTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    checkTv.setGravity(Gravity.CENTER);
                    checkTv.setPadding(dp(ctx, 7), dp(ctx, 2), dp(ctx, 7), dp(ctx, 2));
                    LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
                    klp.leftMargin = dp(ctx, 6);
                    checkTv.setLayoutParams(klp);
                    row.addView(checkTv);

                    row.setTag(new Object[]{iconView, nameTv, pkgTv, badgeTv, checkTv});
                }

                iconView.setImageDrawable(item.icon);
                nameTv.setText(item.label);
                pkgTv.setText(item.pkg);

                boolean isAllow = AppVisibilityPolicy.MODE_ALLOWLIST.equals(mode[0]);
                boolean inExtra = extraHidden.contains(item.pkg);
                boolean sysAutoVisible = isAllow && excludeSys[0] && item.system
                        && !item.forcedHidden && !item.essential && !inExtra;
                boolean locked = item.essential || item.forcedHidden || sysAutoVisible;
                boolean isSel = !locked && selected.contains(item.pkg);

                if (item.forcedHidden) {
                    badgeTv.setText(Lang.t("恒隐", "Hidden", "固定隠"));
                    badgeTv.setTextColor(RED);
                    badgeTv.setBackground(box(ctx, 0x22E55353, 0x73E55353, 8));
                    badgeTv.setVisibility(View.VISIBLE);
                    checkTv.setVisibility(View.GONE);
                    row.setBackground(box(ctx, CARD2, STROKE, 11));
                    row.setAlpha(0.60f);
                } else if (item.essential) {
                    badgeTv.setText(Lang.t("必需", "Core", "必須"));
                    badgeTv.setTextColor(SAND);
                    badgeTv.setBackground(box(ctx, 0x1F7DD3D8, 0x667DD3D8, 8));
                    badgeTv.setVisibility(View.VISIBLE);
                    checkTv.setVisibility(View.GONE);
                    row.setBackground(box(ctx, CARD2, STROKE, 11));
                    row.setAlpha(0.72f);
                } else if (inExtra) {
                    badgeTv.setText(Lang.t("预设隐", "Preset", "プリセット隠"));
                    badgeTv.setTextColor(RED);
                    badgeTv.setBackground(box(ctx, 0x22E55353, 0x73E55353, 8));
                    badgeTv.setVisibility(View.VISIBLE);
                    checkTv.setVisibility(View.GONE);
                    row.setBackground(box(ctx, 0x16E55353, 0x66E55353, 11));
                    row.setAlpha(0.92f);
                } else if (sysAutoVisible) {
                    badgeTv.setText(Lang.t("系统免隐", "Sys", "システム"));
                    badgeTv.setTextColor(DIM);
                    badgeTv.setBackground(box(ctx, BG, STROKE, 8));
                    badgeTv.setVisibility(View.VISIBLE);
                    checkTv.setVisibility(View.GONE);
                    row.setBackground(box(ctx, CARD2, STROKE, 11));
                    row.setAlpha(0.68f);
                } else {
                    row.setAlpha(1f);
                    String catLabel = categoryLabel(item.category);
                    if (catLabel != null) {
                        badgeTv.setText(catLabel);
                        badgeTv.setTextColor(AMBER);
                        badgeTv.setBackground(box(ctx, 0x1FE5A93B, 0x66E5A93B, 8));
                        badgeTv.setVisibility(View.VISIBLE);
                    } else {
                        badgeTv.setVisibility(View.GONE);
                    }
                    if (isSel) {
                        boolean isDeny = AppVisibilityPolicy.MODE_DENYLIST.equals(mode[0]);
                        int ac = isDeny ? RED : ACCENT;
                        int af = isDeny ? 0x22E55353 : 0x1F2EC4B6;
                        int as = isDeny ? 0x73E55353 : 0x732EC4B6;
                        checkTv.setText(isDeny
                                ? Lang.t("隐藏", "Hide", "隠")
                                : Lang.t("放行", "Allow", "許可"));
                        checkTv.setTextColor(ac);
                        checkTv.setBackground(box(ctx, af, as, 8));
                        checkTv.setVisibility(View.VISIBLE);
                        row.setBackground(box(ctx, isDeny ? 0x1AE55353 : 0x182EC4B6, ac, 11));
                    } else {
                        checkTv.setVisibility(View.GONE);
                        row.setBackground(box(ctx, CARD2, STROKE, 11));
                    }
                }

                row.setOnClickListener(v -> {
                    if (item.essential || item.forcedHidden) {
                        return;
                    }
                    if (inExtra) {
                        extraHidden.remove(item.pkg);
                        refreshUi[0].run();
                        return;
                    }
                    if (sysAutoVisible) {
                        return;
                    }
                    if (selected.contains(item.pkg)) {
                        selected.remove(item.pkg);
                    } else {
                        selected.add(item.pkg);
                    }
                    refreshUi[0].run();
                });
                return row;
            }
        };
        listView.setAdapter(adapter);

        refreshUi[0] = () -> {
            int featOn = (hideDev[0] ? 1 : 0) + (hideA11y[0] ? 1 : 0) + (hideIme[0] ? 1 : 0);
            int presetActiveCount = checkedGroups.size() + featOn;
            presetBtn.setText(presetActiveCount > 0
                    ? Lang.t("设置预设 · ", "Presets · ") + presetActiveCount
                    : Lang.t("设置预设", "Presets"));

            // 1. Mode bar
            modeSeg.removeAllViews();
            String[][] modes = {
                    {AppVisibilityPolicy.MODE_ALLOWLIST, Lang.t("白名单", "Allowlist", "ホワイトリスト")},
                    {AppVisibilityPolicy.MODE_DENYLIST, Lang.t("黑名单", "Denylist", "ブラックリスト")},
                    {AppVisibilityPolicy.MODE_DEFAULT, Lang.t("默认", "Default", "標準")},
            };
            for (final String[] m : modes) {
                boolean active = m[0].equals(mode[0]);
                TextView btn = txt(ctx, m[1], 11f, active ? FG : DIM, active);
                btn.setGravity(Gravity.CENTER);
                btn.setSingleLine(true);
                btn.setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7));
                if (active) {
                    btn.setBackground(box(ctx, CARD2, STROKE2, 9));
                }
                btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                bindPress(btn);
                btn.setOnClickListener(v -> {
                    if (!m[0].equals(mode[0])) {
                        mode[0] = m[0];
                        refreshUi[0].run();
                    }
                });
                modeSeg.addView(btn);
            }

            boolean isDefault = AppVisibilityPolicy.MODE_DEFAULT.equals(mode[0]);
            boolean isAllow = AppVisibilityPolicy.MODE_ALLOWLIST.equals(mode[0]);

            // 2. HMA policy toggles row
            toggleRow.removeAllViews();
            if (isDefault) {
                toggleRow.setVisibility(View.GONE);
            } else {
                toggleRow.setVisibility(View.VISIBLE);
                if (isAllow) {
                    TextView sysTgl = togglePill(ctx,
                            excludeSys[0]
                                    ? Lang.t("排除系统应用 ✓", "Exclude System ✓")
                                    : Lang.t("含系统严格", "Include System"),
                            excludeSys[0]);
                    sysTgl.setOnClickListener(v -> {
                        excludeSys[0] = !excludeSys[0];
                        refreshUi[0].run();
                    });
                    LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
                    lp1.rightMargin = dp(ctx, 6);
                    toggleRow.addView(sysTgl, lp1);
                }
                TextView strictTgl = togglePill(ctx,
                        strictHide[0]
                                ? Lang.t("深度防探测 ✓", "Deep Shield ✓")
                                : Lang.t("仅 PMS 过滤", "PMS Only"),
                        strictHide[0]);
                strictTgl.setOnClickListener(v -> {
                    strictHide[0] = !strictHide[0];
                    refreshUi[0].run();
                });
                toggleRow.addView(strictTgl, new LinearLayout.LayoutParams(0, -2, 1f));
            }

            pickerBody.setVisibility(isDefault ? View.GONE : View.VISIBLE);
            if (isDefault) {
                String extraTag = extraHidden.isEmpty() ? ""
                        : Lang.t(" · 预设隐 ", " · preset ") + extraHidden.size();
                summaryTv.setText(Lang.t("默认 · 恒隐 Root/框架", "Default · Root")
                        + extraTag + Lang.t(" · 系统特征 ", " · sys ") + featOn + "/3");
                return;
            }

            loadingTv.setVisibility(loaded[0] ? View.GONE : View.VISIBLE);
            listView.setVisibility(loaded[0] ? View.VISIBLE : View.GONE);

            // 3. Filter chips with live counters
            int riskCount = 0;
            int userCount = 0;
            int sysCount = 0;
            for (AppItem a : allApps) {
                if (a.system) sysCount++;
                else userCount++;
                if (a.sensitive() && !a.essential) riskCount++;
            }
            chipRow.removeAllViews();
            String[][] filters = {
                    {"USER", Lang.t("第三方 ", "Apps ") + userCount},
                    {"RISK", Lang.t("风险 ", "Risk ") + riskCount},
                    {"SELECTED", Lang.t("已选 ", "Picked ") + selected.size()},
                    {"SYSTEM", Lang.t("系统 ", "Sys ") + sysCount},
            };
            for (final String[] f : filters) {
                boolean on = f[0].equals(filter[0]);
                TextView ch = txt(ctx, f[1], 10, on ? ACCENT : DIM, on);
                ch.setTypeface(Typeface.MONOSPACE, on ? Typeface.BOLD : Typeface.NORMAL);
                ch.setSingleLine(true);
                ch.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
                ch.setBackground(box(ctx, on ? 0x1F2EC4B6 : CARD2, on ? 0x6B2EC4B6 : STROKE, 12));
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
                flp.rightMargin = dp(ctx, 6);
                ch.setLayoutParams(flp);
                bindPress(ch);
                ch.setOnClickListener(v -> {
                    filter[0] = f[0];
                    refreshUi[0].run();
                });
                chipRow.addView(ch);
            }

            // 4. Filter & refresh ListView
            visibleApps.clear();
            String q = query[0].toLowerCase(Locale.ROOT);
            for (AppItem a : allApps) {
                if (!q.isEmpty()) {
                    if (!a.lowerLabel.contains(q) && !a.lowerPkg.contains(q)) {
                        continue;
                    }
                } else {
                    if ("USER".equals(filter[0]) && a.system) continue;
                    if ("SYSTEM".equals(filter[0]) && !a.system) continue;
                    if ("RISK".equals(filter[0]) && (!a.sensitive() || a.essential)) continue;
                    if ("SELECTED".equals(filter[0]) && !selected.contains(a.pkg)) continue;
                }
                visibleApps.add(a);
            }
            adapter.notifyDataSetChanged();

            // 5. Live mono summary
            String extraPart = extraHidden.isEmpty() ? ""
                    : Lang.t(" · 预设隐 ", " · +hide ") + extraHidden.size();
            String featPart = Lang.t(" · 特征 ", " · sys ") + featOn + "/3";
            if (isAllow) {
                String headTag = excludeSys[0]
                        ? Lang.t("白名单 · 放行 ", "Allow · ")
                        : Lang.t("严苛白 · 放行 ", "Strict · ");
                summaryTv.setText(headTag + selected.size() + extraPart + featPart);
            } else {
                summaryTv.setText(Lang.t("黑名单 · 隐藏 ", "Deny · ")
                        + selected.size() + extraPart + featPart);
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query[0] = s == null ? "" : s.toString().trim();
                refreshUi[0].run();
            }
        });

        refreshUi[0].run();

        // Load installed packages asynchronously so the sheet opens instantaneously.
        new Thread(() -> {
            List<AppItem> built = loadInstalledApps(ctx, targetPkg, selected);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                allApps.clear();
                allApps.addAll(built);
                loaded[0] = true;
                refreshUi[0].run();
            });
        }, "cw-appvis-load").start();

        presentBottomSheet(ctx, dialog, sheet);
    }

    private interface PresetApplyCallback {
        void onApply(Set<String> checkedGroups,
                     Set<String> matchedToHide,
                     Set<String> matchedSystemToHide,
                     boolean hideDev,
                     boolean hideA11y,
                     boolean hideIme);
    }

    /**
     * HMA "设置预设" BottomSheet:
     * 8 live-scanned app categories (LSPosed/Xposed, Root Managers, Shizuku Modules, Suspicious,
     * Accessibility Apps, Detectors, Custom ROM Apps, Input Methods) with expandable matched-app
     * inspection + 3 system feature shields (Developer Options, Accessibility Feature, Input Method).
     */
    private static void showHmaPresetSettingsSheet(final Context ctx,
                                                   final String targetPkg,
                                                   final Set<String> initialCheckedGroups,
                                                   final boolean initialHideDev,
                                                   final boolean initialHideA11y,
                                                   final boolean initialHideIme,
                                                   final PresetApplyCallback onApply) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final Set<String> checked = new LinkedHashSet<String>(initialCheckedGroups);
        final Set<String> expanded = new LinkedHashSet<String>();
        final boolean[] hDev = {initialHideDev};
        final boolean[] hA11y = {initialHideA11y};
        final boolean[] hIme = {initialHideIme};
        final HmaPresetScanner.ScanResult[] scanRef = {null};

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 18));

        View handle = new View(ctx);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(STROKE2);
        hbg.setCornerRadius(dp(ctx, 2));
        handle.setBackground(hbg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(ctx, 11);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Header
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(ctx, 10);
        head.setLayoutParams(headLp);
        TextView title = txt(ctx, Lang.t("设置预设", "Preset Categories", "プリセット設定"),
                15.5f, FG, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);
        TextView close = txt(ctx, "✕", 14, DIM, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(ctx, CARD2, STROKE, 14));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        bindPress(close);
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close);
        sheet.addView(head);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        TextView scanningTv = txt(ctx, Lang.t("扫描本机预设命中…", "Scanning device…"), 11, DIM, false);
        scanningTv.setTypeface(Typeface.MONOSPACE);
        scanningTv.setGravity(Gravity.CENTER);
        scanningTv.setPadding(0, dp(ctx, 40), 0, dp(ctx, 40));
        content.addView(scanningTv);

        int maxH = Math.min(dp(ctx, 480),
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.62f));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, maxH));

        // Footer
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(ctx, 10);
        foot.setLayoutParams(flp);

        final TextView selectAllBtn = txt(ctx, Lang.t("全选命中", "Select all"), 11.5f, SAND, true);
        selectAllBtn.setGravity(Gravity.CENTER);
        selectAllBtn.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        selectAllBtn.setBackground(box(ctx, CARD2, 0x557DD3D8, 12));
        bindPress(selectAllBtn);
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(-2, -2);
        salp.rightMargin = dp(ctx, 8);
        foot.addView(selectAllBtn, salp);

        final TextView applyBtn = txt(ctx, Lang.t("应用预设", "Apply Presets"), 12, 0xFF041918, true);
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        applyBtn.setBackground(box(ctx, ACCENT, ACCENT, 12));
        bindPress(applyBtn);
        foot.addView(applyBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.addView(foot);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            HmaPresetScanner.ScanResult res = scanRef[0];
            if (res == null) return;
            content.removeAllViews();

            // Section 1: 8 App Detection Presets
            TextView sec1 = txt(ctx, Lang.t("应用检测预设", "APP CATEGORIES"), 9.5f, DIM, true);
            sec1.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams s1lp = new LinearLayout.LayoutParams(-1, -2);
            s1lp.bottomMargin = dp(ctx, 6);
            content.addView(sec1, s1lp);

            for (final HmaPresetScanner.PresetGroup g : res.groups) {
                boolean isChecked = checked.contains(g.id);
                boolean isExp = expanded.contains(g.id);
                int cnt = g.apps.size();

                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9));
                card.setBackground(box(ctx,
                        isChecked ? 0x182EC4B6 : CARD2,
                        isChecked ? ACCENT : STROKE, 11));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.bottomMargin = dp(ctx, 6);
                card.setLayoutParams(clp);

                LinearLayout topRow = new LinearLayout(ctx);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                // Left toggle pill
                TextView chk = txt(ctx,
                        isChecked ? Lang.t("已选", "ON") : Lang.t("未选", "OFF"),
                        9f, isChecked ? 0xFF041918 : DIM, true);
                chk.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                chk.setGravity(Gravity.CENTER);
                chk.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
                chk.setBackground(box(ctx, isChecked ? ACCENT : BG,
                        isChecked ? ACCENT : STROKE, 8));
                LinearLayout.LayoutParams chklp = new LinearLayout.LayoutParams(-2, -2);
                chklp.rightMargin = dp(ctx, 10);
                chk.setLayoutParams(chklp);
                bindPress(chk);
                chk.setOnClickListener(v -> {
                    if (checked.contains(g.id)) checked.remove(g.id);
                    else checked.add(g.id);
                    render[0].run();
                });
                topRow.addView(chk);

                // Title (tap to expand/collapse matched apps)
                TextView nameTv = txt(ctx, g.title, 12f, cnt > 0 ? FG : DIM, true);
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.END);
                nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                topRow.addView(nameTv);

                // Hit count badge
                TextView cntTv = txt(ctx,
                        cnt > 0 ? (cnt + Lang.t(" 项", "")) : Lang.t("0 项", "0"),
                        10f, cnt > 0 ? (isChecked ? ACCENT : AMBER) : MUTE, cnt > 0);
                cntTv.setTypeface(Typeface.MONOSPACE, cnt > 0 ? Typeface.BOLD : Typeface.NORMAL);
                topRow.addView(cntTv);

                topRow.setOnClickListener(v -> {
                    if (cnt == 0) {
                        if (checked.contains(g.id)) checked.remove(g.id);
                        else checked.add(g.id);
                    } else {
                        if (expanded.contains(g.id)) expanded.remove(g.id);
                        else expanded.add(g.id);
                    }
                    render[0].run();
                });
                card.addView(topRow);

                // Expandable matched-app list inside the preset group
                if (isExp && cnt > 0) {
                    View div = new View(ctx);
                    div.setBackgroundColor(STROKE);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(ctx, 1));
                    dlp.topMargin = dp(ctx, 8);
                    dlp.bottomMargin = dp(ctx, 6);
                    card.addView(div, dlp);

                    for (HmaPresetScanner.MatchedApp ma : g.apps) {
                        LinearLayout appRow = new LinearLayout(ctx);
                        appRow.setOrientation(LinearLayout.HORIZONTAL);
                        appRow.setGravity(Gravity.CENTER_VERTICAL);
                        appRow.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4));

                        ImageView iv = new ImageView(ctx);
                        int isz = dp(ctx, 22);
                        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
                        ilp.rightMargin = dp(ctx, 8);
                        iv.setLayoutParams(ilp);
                        iv.setImageDrawable(ma.icon);
                        appRow.addView(iv);

                        LinearLayout mid = new LinearLayout(ctx);
                        mid.setOrientation(LinearLayout.VERTICAL);
                        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                        TextView al = txt(ctx, ma.label, 10.5f, FG, false);
                        al.setSingleLine(true);
                        al.setEllipsize(TextUtils.TruncateAt.END);
                        mid.addView(al);
                        TextView ap = txt(ctx, ma.pkg, 8.5f, DIM, false);
                        ap.setTypeface(Typeface.MONOSPACE);
                        ap.setSingleLine(true);
                        ap.setEllipsize(TextUtils.TruncateAt.END);
                        mid.addView(ap);
                        appRow.addView(mid);

                        if (ma.forcedHidden) {
                            TextView tg = txt(ctx, Lang.t("恒隐", "Hidden"), 8f, RED, true);
                            tg.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            appRow.addView(tg);
                        } else if (ma.essential) {
                            TextView tg = txt(ctx, Lang.t("必需", "Core"), 8f, SAND, true);
                            tg.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            appRow.addView(tg);
                        } else if (ma.system) {
                            TextView tg = txt(ctx, Lang.t("系统", "Sys"), 8f, DIM, false);
                            tg.setTypeface(Typeface.MONOSPACE);
                            appRow.addView(tg);
                        }
                        card.addView(appRow);
                    }
                }
                content.addView(card);
            }

            // Section 2: 3 System Feature Shields (开发者选项 / 无障碍功能 / 输入法特征)
            TextView sec2 = txt(ctx, Lang.t("系统特征隐匿", "SYSTEM FEATURES"), 9.5f, DIM, true);
            sec2.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams s2lp = new LinearLayout.LayoutParams(-1, -2);
            s2lp.topMargin = dp(ctx, 8);
            s2lp.bottomMargin = dp(ctx, 6);
            content.addView(sec2, s2lp);

            String devState = (res.devOptionsOn || res.adbOn)
                    ? (res.adbOn ? Lang.t("本机已开 · ADB", "ON · ADB") : Lang.t("本机已开", "ON"))
                    : Lang.t("本机关闭", "OFF");
            content.addView(featureToggleCard(ctx,
                    Lang.t("开发者选项", "Developer Options"),
                    devState, res.devOptionsOn || res.adbOn, hDev[0],
                    () -> { hDev[0] = !hDev[0]; render[0].run(); }));

            String a11yState = res.enabledA11yCount > 0
                    ? Lang.t("已启 ", "Active ") + res.enabledA11yCount + Lang.t(" 项", "")
                    : Lang.t("本机关闭", "OFF");
            content.addView(featureToggleCard(ctx,
                    Lang.t("无障碍功能", "Accessibility"),
                    a11yState, res.enabledA11yCount > 0, hA11y[0],
                    () -> { hA11y[0] = !hA11y[0]; render[0].run(); }));

            String imeState = res.defaultImeThirdParty
                    ? Lang.t("默认第三方", "3rd-party IME")
                    : Lang.t("系统默认", "System IME");
            content.addView(featureToggleCard(ctx,
                    Lang.t("输入法特征", "Input Method"),
                    imeState, res.defaultImeThirdParty, hIme[0],
                    () -> { hIme[0] = !hIme[0]; render[0].run(); }));

            boolean allChecked = checked.size() >= res.groups.size();
            selectAllBtn.setText(allChecked
                    ? Lang.t("全不选", "Clear all")
                    : Lang.t("全选", "Select all"));
        };

        selectAllBtn.setOnClickListener(v -> {
            HmaPresetScanner.ScanResult res = scanRef[0];
            if (res == null) return;
            if (checked.size() >= res.groups.size()) {
                checked.clear();
            } else {
                for (HmaPresetScanner.PresetGroup g : res.groups) {
                    checked.add(g.id);
                }
                hDev[0] = true;
                hA11y[0] = true;
                hIme[0] = true;
            }
            render[0].run();
        });

        applyBtn.setOnClickListener(v -> {
            HmaPresetScanner.ScanResult res = scanRef[0];
            Set<String> allMatched = new LinkedHashSet<String>();
            Set<String> sysMatched = new LinkedHashSet<String>();
            if (res != null) {
                for (HmaPresetScanner.PresetGroup g : res.groups) {
                    if (!checked.contains(g.id)) continue;
                    for (HmaPresetScanner.MatchedApp ma : g.apps) {
                        if (ma.essential || ma.forcedHidden) continue;
                        allMatched.add(ma.pkg);
                        if (ma.system) {
                            sysMatched.add(ma.pkg);
                        }
                    }
                }
            }
            dlg.dismiss();
            if (onApply != null) {
                onApply.onApply(checked, allMatched, sysMatched, hDev[0], hA11y[0], hIme[0]);
            }
        });

        new Thread(() -> {
            HmaPresetScanner.ScanResult res = HmaPresetScanner.scan(ctx, targetPkg);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dlg.isShowing()) return;
                scanRef[0] = res;
                render[0].run();
            });
        }, "cw-hma-preset-scan").start();

        presentBottomSheet(ctx, dlg, sheet);
    }

    private static View featureToggleCard(Context ctx, String title, String liveState,
                                          boolean liveActive, boolean hiddenOn, Runnable onToggle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10));
        row.setBackground(box(ctx, hiddenOn ? 0x182EC4B6 : CARD2,
                hiddenOn ? ACCENT : STROKE, 11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(ctx, 6);
        row.setLayoutParams(lp);

        TextView t = txt(ctx, title, 12f, FG, true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);

        TextView st = txt(ctx, liveState, 9.5f, liveActive ? AMBER : DIM, false);
        st.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.rightMargin = dp(ctx, 9);
        st.setLayoutParams(slp);
        row.addView(st);

        TextView pill = txt(ctx,
                hiddenOn ? Lang.t("已隐 ✓", "Hidden ✓") : Lang.t("未隐", "Off"),
                9.5f, hiddenOn ? ACCENT : DIM, hiddenOn);
        pill.setTypeface(Typeface.MONOSPACE, hiddenOn ? Typeface.BOLD : Typeface.NORMAL);
        pill.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        pill.setBackground(box(ctx, hiddenOn ? 0x222EC4B6 : BG,
                hiddenOn ? ACCENT : STROKE, 8));
        row.addView(pill);

        bindPress(row);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private static void presentBottomSheet(Context ctx, Dialog dialog, View sheet) {
        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.62f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            float r = dp(ctx, 20);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            bg.setStroke(dp(ctx, 1), STROKE2);
            sheet.setBackground(bg);
            sheet.setTranslationY(dp(ctx, 36));
            sheet.setAlpha(0f);
            sheet.post(() -> sheet.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator())
                    .start());
        }
        dialog.show();
    }

    private static String categoryLabel(int category) {
        switch (category) {
            case AppVisibilityPolicy.CAT_PROXY:
                return Lang.t("代理", "Proxy", "プロキシ");
            case AppVisibilityPolicy.CAT_DOMESTIC:
                return Lang.t("国内", "CN", "国内");
            case AppVisibilityPolicy.CAT_DEV:
                return Lang.t("调试", "Dev", "開発");
            default:
                return null;
        }
    }

    private static List<AppItem> loadInstalledApps(Context ctx, String targetPkg,
                                                   final Set<String> initialSelected) {
        List<AppItem> out = new ArrayList<AppItem>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null || ai.packageName.isEmpty()) {
                    continue;
                }
                String pkg = ai.packageName;
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                boolean forcedHidden = AppVisibilityPolicy.isForcedHidden(pkg);
                boolean essential = !forcedHidden
                        && AppVisibilityPolicy.isEssentialPackage(pkg, targetPkg);
                int category = AppVisibilityPolicy.categoryOf(pkg);
                CharSequence cs = pm.getApplicationLabel(ai);
                String label = cs == null || cs.length() == 0 ? pkg : cs.toString();
                Drawable icon = null;
                try {
                    icon = pm.getApplicationIcon(ai);
                } catch (Throwable ignored) {
                }
                out.add(new AppItem(pkg, label, isSys, essential, forcedHidden, category, icon));
            }
            Collections.sort(out, new Comparator<AppItem>() {
                @Override public int compare(AppItem a, AppItem b) {
                    boolean sa = initialSelected.contains(a.pkg);
                    boolean sb = initialSelected.contains(b.pkg);
                    if (sa != sb) return sa ? -1 : 1;
                    if (a.sensitive() != b.sensitive()) return a.sensitive() ? -1 : 1;
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static TextView togglePill(Context ctx, String label, boolean on) {
        TextView t = txt(ctx, label, 10f, on ? ACCENT : DIM, on);
        t.setTypeface(Typeface.MONOSPACE, on ? Typeface.BOLD : Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        t.setBackground(box(ctx, on ? 0x1C2EC4B6 : CARD2, on ? 0x6B2EC4B6 : STROKE, 10));
        bindPress(t);
        return t;
    }

    private static GradientDrawable box(Context ctx, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), stroke);
        return g;
    }

    private static TextView txt(Context ctx, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static void bindPress(View view) {
        view.setClickable(true);
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(75).start();
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

    private static int dp(Context ctx, float v) {
        return UiSection.dp(ctx, v);
    }
}
