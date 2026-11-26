package com.walter.claudewilson;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.BleProfile;
import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.PersonaEditRules;
import com.walter.claudewilson.model.PersonaSynth;
import com.walter.claudewilson.model.SensorProfile;
import com.walter.claudewilson.model.SimPolicy;
import com.walter.claudewilson.model.WifiProfile;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * Unified dark-slate BottomSheet editors for all Persona Space workbench fields.
 *
 * <p>Replaces the legacy multi-step AlertDialogs with 4 atomic, linkage-aware sheets:
 * Carrier Tuple, Locale &amp; Timezone, Device &amp; CSC, and Android ID.
 */
final class PersonaEditSheets {

    private static final int BG = UiSection.BG;
    private static final int CARD = UiSection.CARD;
    private static final int CARD2 = UiSection.CARD2;
    private static final int STROKE = UiSection.STROKE;
    private static final int STROKE2 = 0xFF2B454B;
    private static final int FG = UiSection.FG;
    private static final int DIM = UiSection.DIM;
    private static final int MUTE = UiSection.MUTE;
    private static final int CYAN = UiSection.CYAN;
    private static final int GREEN = UiSection.GREEN;
    private static final int AMBER = UiSection.AMBER;
    private static final int RED = UiSection.RED;
    private static final int ICE = 0xFFA6D8EE;

    private static final SecureRandom SECURE_RNG = new SecureRandom();

    interface CarrierCallback {
        void onSave(boolean keepRealSim, String op, String carrier);
    }

    interface LocaleTzCallback {
        void onSave(String locale, String zone, boolean syncLocation);
    }

    interface DeviceCallback {
        void onSelectPreset(int presetIndex, boolean hookOnly);
        void onSelectCustom(String[] customRow, long seed, boolean hookOnly);
        void onRunCscScript(String targetCscOrEmptyOrNull, String script);
    }

    interface AidCallback {
        void onSave(String aidOrNullForNative);
    }

    static final class CscOption {
        final String label;
        final String script;
        final String target; // "" = restore, null = prop-only, else new target

        CscOption(String label, String script, String target) {
            this.label = label;
            this.script = script;
            this.target = target;
        }
    }

    private PersonaEditSheets() {
    }

    // =========================================================================
    // 1. CARRIER TUPLE SHEET (SIM 策略 + MCC/MNC + 运营商 + 移动 IPv6 联动)
    // =========================================================================
    static void showCarrierSheet(final Context ctx, final Regions.R region,
                                 final SimPolicy.Decision simDecision,
                                 final boolean currentKeepReal,
                                 final String currentOp, final String currentCarrier,
                                 final long profileSeed,
                                 final CarrierCallback callback) {
        if (ctx == null || region == null || callback == null) return;
        final List<RegionPersona.CarrierEntry> presets = RegionPersona.carriersFor(ctx, region);
        final String isoLow = region.iso.toLowerCase(Locale.ROOT);
        final String isoUp = region.iso.toUpperCase(Locale.ROOT);

        final boolean[] keepReal = new boolean[]{simDecision.simEligible && currentKeepReal};
        final String[] draftOp = new String[]{currentOp == null ? "" : currentOp};
        final String[] draftCar = new String[]{currentCarrier == null ? "" : currentCarrier};
        final boolean[] customOpen = new boolean[]{false};

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx,
                Lang.t("SIM 运营商 · ", "SIM Carrier · ", "SIM キャリア · ")
                        + Regions.flagOf(region.iso) + " " + region.dispName(),
                dialog));

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svlp.topMargin = dp(ctx, 10);
        sheet.addView(sv, svlp);

        final LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(ctx, 10), 0, 0);
        sheet.addView(foot);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            foot.removeAllViews();

            // SIM strategy segmented bar
            LinearLayout segBar = new LinearLayout(ctx);
            segBar.setOrientation(LinearLayout.HORIZONTAL);
            segBar.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            segBar.setBackground(box(ctx, BG, STROKE, 12));
            boolean restricted = SimPolicy.REASON_RESTRICTED_SIM.equals(simDecision.reason)
                    || SimPolicy.REASON_RESTRICTED_PEER.equals(simDecision.reason);
            String spoofLbl = restricted
                    ? Lang.t("禁区伪装 · ", "Spoof · ", "制限偽装 · ") + isoUp
                    : Lang.t("伪装覆写 · ", "Spoof · ", "偽装 · ") + isoUp;
            String realLbl = simDecision.simEligible
                    ? Lang.t("保留原卡 · ", "Real · ", "実SIM · ")
                            + simDecision.resolvedIso.toUpperCase(Locale.ROOT)
                    : Lang.t("保留原卡 🔒", "Real 🔒", "実SIM 🔒");

            TextView segSpoof = segButton(ctx, spoofLbl, !keepReal[0], false);
            segSpoof.setOnClickListener(v -> {
                if (keepReal[0]) {
                    keepReal[0] = false;
                    render[0].run();
                }
            });
            TextView segReal = segButton(ctx, realLbl, keepReal[0], false);
            if (!simDecision.simEligible) {
                segReal.setAlpha(0.45f);
                segReal.setOnClickListener(v -> Toast.makeText(ctx,
                        restricted
                                ? Lang.t("禁区 SIM 必须伪装", "Restricted SIM must be spoofed",
                                        "制限国 SIM は偽装必須")
                                : Lang.t("未检测到可用物理 SIM", "No eligible physical SIM",
                                        "利用可能な実 SIM なし"),
                        Toast.LENGTH_SHORT).show());
            } else {
                segReal.setOnClickListener(v -> {
                    if (!keepReal[0]) {
                        keepReal[0] = true;
                        render[0].run();
                    }
                });
            }
            segBar.addView(segSpoof, new LinearLayout.LayoutParams(0, -2, 1f));
            segBar.addView(segReal, new LinearLayout.LayoutParams(0, -2, 1f));
            body.addView(segBar);

            if (!keepReal[0]) {
                // Curated carrier cards
                LinearLayout list = new LinearLayout(ctx);
                list.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
                llp.topMargin = dp(ctx, 10);
                list.setLayoutParams(llp);

                boolean ipv6On = new PersonaState(ctx).ipv6SpoofEnabled();
                for (final RegionPersona.CarrierEntry c : presets) {
                    boolean sel = c.op.equals(draftOp[0]) && c.name.equalsIgnoreCase(draftCar[0]);
                    String mobV6 = ipv6On
                            ? Ipv6Prefixes.pickMobilePrefix(ctx, isoLow, c.name, "", profileSeed)
                            : "";
                    String meta = c.op + " · " + isoLow
                            + (mobV6 != null && !mobV6.isEmpty() ? " · " + mobV6 : "");

                    LinearLayout row = optionRow(ctx, sel);
                    LinearLayout mid = new LinearLayout(ctx);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    TextView nm = txt(ctx, c.name, 12.5f, FG, true);
                    nm.setSingleLine(true);
                    mid.addView(nm);
                    TextView mt = mono(ctx, meta, 10, DIM);
                    mt.setSingleLine(true);
                    mt.setEllipsize(TextUtils.TruncateAt.END);
                    mid.addView(mt);
                    row.addView(mid);

                    row.addView(tag(ctx, sel ? "✓" : c.op, sel ? CYAN : DIM,
                            sel ? 0x1F2EC4B6 : CARD, sel ? 0x592EC4B6 : STROKE));
                    row.setOnClickListener(v -> {
                        draftOp[0] = c.op;
                        draftCar[0] = c.name;
                        render[0].run();
                    });
                    list.addView(row);
                }
                body.addView(list);

                // Collapsible custom MCC/MNC + carrier row
                LinearLayout custToggle = optionRow(ctx, customOpen[0]);
                custToggle.setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9));
                TextView ctLbl = txt(ctx,
                        Lang.t("自定义 MCC/MNC 与运营商", "Custom MCC/MNC & carrier",
                                "カスタム MCC/MNC・キャリア"), 11, customOpen[0] ? FG : DIM, false);
                ctLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                custToggle.addView(ctLbl);
                custToggle.setOnClickListener(v -> {
                    customOpen[0] = !customOpen[0];
                    render[0].run();
                });
                body.addView(custToggle);

                if (customOpen[0]) {
                    LinearLayout fields = new LinearLayout(ctx);
                    fields.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
                    flp.bottomMargin = dp(ctx, 8);
                    fields.setLayoutParams(flp);

                    LinearLayout opBox = new LinearLayout(ctx);
                    opBox.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, -2, 1f);
                    olp.rightMargin = dp(ctx, 8);
                    opBox.setLayoutParams(olp);
                    opBox.addView(mono(ctx, "MCC/MNC", 9.5f, DIM));
                    final EditText opIn = darkInput(ctx, draftOp[0], "44020");
                    opIn.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
                    opBox.addView(opIn);

                    LinearLayout carBox = new LinearLayout(ctx);
                    carBox.setOrientation(LinearLayout.VERTICAL);
                    carBox.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.3f));
                    carBox.addView(mono(ctx, Lang.t("运营商名", "Carrier", "キャリア名"), 9.5f, DIM));
                    final EditText carIn = darkInput(ctx, draftCar[0], "SoftBank");
                    carIn.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
                    carBox.addView(carIn);

                    fields.addView(opBox);
                    fields.addView(carBox);
                    body.addView(fields);

                    final TextView[] liveLinkRef = new TextView[1];
                    final TextView[] liveSaveRef = new TextView[1];

                    TextWatcher watcher = new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                        @Override public void afterTextChanged(Editable s) {
                            draftOp[0] = opIn.getText().toString().trim();
                            draftCar[0] = carIn.getText().toString().trim();
                            boolean ok = PersonaEditRules.validOperatorCode(draftOp[0])
                                    && PersonaEditRules.validCarrierName(draftCar[0]);
                            if (liveSaveRef[0] != null) {
                                liveSaveRef[0].setEnabled(ok);
                                liveSaveRef[0].setAlpha(ok ? 1f : 0.4f);
                            }
                            if (liveLinkRef[0] != null) {
                                updateCarrierLinkBanner(ctx, liveLinkRef[0], draftOp[0], draftCar[0], region);
                            }
                        }
                    };
                    opIn.addTextChangedListener(watcher);
                    carIn.addTextChangedListener(watcher);

                    TextView link = linkStrip(ctx, "", false);
                    updateCarrierLinkBanner(ctx, link, draftOp[0], draftCar[0], region);
                    liveLinkRef[0] = link;
                    body.addView(link);

                    buildCarrierFooter(ctx, foot, presets, keepReal, draftOp, draftCar,
                            render[0], dialog, callback, liveSaveRef);
                    return;
                }

                // Linkage strip when custom closed
                TextView link = linkStrip(ctx, "", false);
                updateCarrierLinkBanner(ctx, link, draftOp[0], draftCar[0], region);
                body.addView(link);
            } else {
                TextView link = linkStrip(ctx,
                        Lang.t("保留物理卡 · ", "Preserve real SIM · ")
                                + simDecision.resolvedOp + " · " + simDecision.resolvedIso
                                + " · " + simDecision.resolvedCarrier, false);
                LinearLayout.LayoutParams lkp = new LinearLayout.LayoutParams(-1, -2);
                lkp.topMargin = dp(ctx, 10);
                link.setLayoutParams(lkp);
                body.addView(link);
            }

            buildCarrierFooter(ctx, foot, presets, keepReal, draftOp, draftCar,
                    render[0], dialog, callback, null);
        };
        render[0].run();
        presentBottomSheet(ctx, dialog, sheet);
    }

    private static void updateCarrierLinkBanner(Context ctx, TextView tv,
                                                String op, String car, Regions.R region) {
        String isoUp = region.iso.toUpperCase(Locale.ROOT);
        boolean mccOk = PersonaEditRules.mccMatchesRegion(op, region.mcc);
        int cid = PersonaEditRules.carrierIdFor(op, car);
        if (!PersonaEditRules.validOperatorCode(op)) {
            tv.setText(Lang.t("⚠ MCC/MNC 需 5–6 位数字", "⚠ MCC/MNC must be 5-6 digits",
                    "⚠ MCC/MNC は 5〜6 桁"));
            tv.setTextColor(AMBER);
            tv.setBackground(box(ctx, 0x1FE5A93B, 0x73E5A93B, 11));
        } else if (!mccOk) {
            tv.setText(Lang.t("⚠ MCC ", "⚠ MCC ") + op.substring(0, 3)
                    + Lang.t(" 不属于 ", " outside ") + isoUp);
            tv.setTextColor(AMBER);
            tv.setBackground(box(ctx, 0x1FE5A93B, 0x73E5A93B, 11));
        } else {
            String cidTag = cid > 0 ? " · CID " + cid : "";
            tv.setText(Lang.t("联动: 拜访网 ", "Sync: net ") + op + " · "
                    + (car.isEmpty() ? "—" : car) + cidTag + " · v6");
            tv.setTextColor(ICE);
            tv.setBackground(box(ctx, 0x122EC4B6, 0x472EC4B6, 11));
        }
    }

    private static void buildCarrierFooter(Context ctx, LinearLayout foot,
                                           List<RegionPersona.CarrierEntry> presets,
                                           boolean[] keepReal, String[] draftOp, String[] draftCar,
                                           Runnable rerender, Dialog dialog,
                                           CarrierCallback callback, TextView[] saveOut) {
        if (!keepReal[0] && !presets.isEmpty()) {
            TextView reset = secondaryBtn(ctx, Lang.t("默认", "Default", "既定"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.rightMargin = dp(ctx, 8);
            reset.setLayoutParams(rlp);
            reset.setOnClickListener(v -> {
                draftOp[0] = presets.get(0).op;
                draftCar[0] = presets.get(0).name;
                rerender.run();
            });
            foot.addView(reset);
        }
        boolean ok = keepReal[0] || (PersonaEditRules.validOperatorCode(draftOp[0])
                && PersonaEditRules.validCarrierName(draftCar[0]));
        TextView save = primaryBtn(ctx, Lang.t("保存并同步", "Save & Sync", "保存して同期"), false);
        save.setEnabled(ok);
        save.setAlpha(ok ? 1f : 0.4f);
        save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        save.setOnClickListener(v -> {
            dialog.dismiss();
            callback.onSave(keepReal[0], draftOp[0], draftCar[0]);
        });
        if (saveOut != null) saveOut[0] = save;
        foot.addView(save);
    }

    // =========================================================================
    // 2. LOCALE & TIMEZONE SHEET (目标语言 + 区域置顶时区 + GPS/Wi-Fi 联动)
    // =========================================================================
    static void showLocaleTzSheet(final Context ctx, final Regions.R region,
                                  final String currentLocale, final String currentZone,
                                  final LocaleTzCallback callback) {
        if (ctx == null || region == null || callback == null) return;
        final List<TimeZoneIndex.Entry> globalAll = TimeZoneIndex.all();
        final Set<String> regionZoneSet = new LinkedHashSet<String>();
        final List<TimeZoneIndex.Entry> regionZones = new ArrayList<TimeZoneIndex.Entry>();
        long now = System.currentTimeMillis();
        for (String z : region.zones) {
            if (z == null || z.isEmpty() || regionZoneSet.contains(z)) continue;
            regionZoneSet.add(z);
            int off = TimeZone.getTimeZone(z).getOffset(now) / 60000;
            regionZones.add(new TimeZoneIndex.Entry(z, off, "UTC" + Regions.utcFmt(off)));
        }

        final List<String> localeOptions = new ArrayList<String>();
        for (String l : region.locales) {
            if (l != null && !l.isEmpty() && !localeOptions.contains(l)) {
                localeOptions.add(l);
            }
        }
        if (currentLocale != null && !currentLocale.isEmpty()
                && !"—".equals(currentLocale) && !localeOptions.contains(currentLocale)) {
            localeOptions.add(0, currentLocale);
        }

        final String[] draftLocale = new String[]{
                currentLocale != null && !"—".equals(currentLocale) ? currentLocale
                        : (!localeOptions.isEmpty() ? localeOptions.get(0) : "en-US")};
        final String[] draftZone = new String[]{
                currentZone != null && !"—".equals(currentZone) ? currentZone : region.dispTz()};
        final boolean[] syncGps = new boolean[]{true};

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx,
                Lang.t("语言与时区 · ", "Locale & TZ · ", "言語・TZ · ")
                        + Regions.flagOf(region.iso) + " " + region.dispName(),
                dialog));

        // Locale chips row
        final LinearLayout locBar = new LinearLayout(ctx);
        locBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView locScroll = new HorizontalScrollView(ctx);
        locScroll.setHorizontalScrollBarEnabled(false);
        locScroll.addView(locBar);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(-1, -2);
        lslp.topMargin = dp(ctx, 10);
        sheet.addView(locScroll, lslp);

        // Search input
        final EditText search = darkInput(ctx, "",
                Lang.t("搜索时区 / UTC…", "Search timezone / UTC…", "タイムゾーン / UTC を検索…"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(ctx, 8);
        slp.bottomMargin = dp(ctx, 8);
        sheet.addView(search, slp);

        // Recycling ListView for region-pinned + global timezones
        final ListView listView = new ListView(ctx);
        listView.setVerticalScrollBarEnabled(false);
        listView.setDivider(null);
        listView.setDividerHeight(dp(ctx, 6));
        listView.setSelector(android.R.color.transparent);
        int maxListH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.34f);
        sheet.addView(listView, new LinearLayout.LayoutParams(-1, maxListH));

        // Linkage strip container
        final LinearLayout linkBox = new LinearLayout(ctx);
        linkBox.setOrientation(LinearLayout.HORIZONTAL);
        linkBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, -2);
        lbp.topMargin = dp(ctx, 8);
        sheet.addView(linkBox, lbp);

        // Footer
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(ctx, 10), 0, 0);
        TextView applyBtn = primaryBtn(ctx, Lang.t("应用语言与时区", "Apply Locale & TZ", "言語・TZを適用"), false);
        applyBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        applyBtn.setOnClickListener(v -> {
            dialog.dismiss();
            boolean inRegion = regionZoneSet.contains(draftZone[0]);
            callback.onSave(draftLocale[0], draftZone[0], inRegion && syncGps[0]);
        });
        foot.addView(applyBtn);
        sheet.addView(foot);

        final List<TimeZoneIndex.Entry> filtered = new ArrayList<TimeZoneIndex.Entry>();
        final String isoUp = region.iso.toUpperCase(Locale.ROOT);

        final Runnable refreshLink = () -> {
            linkBox.removeAllViews();
            boolean inRegion = regionZoneSet.contains(draftZone[0]);
            if (!inRegion) {
                linkBox.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
                linkBox.setBackground(box(ctx, 0x1FE5A93B, 0x73E5A93B, 11));
                TextView w = mono(ctx, "⚠ " + draftZone[0]
                        + Lang.t(" 非 ", " outside ") + region.dispName()
                        + Lang.t(" 时区", " zone"), 10, AMBER);
                w.setSingleLine(true);
                w.setEllipsize(TextUtils.TruncateAt.END);
                w.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                linkBox.addView(w);
                linkBox.addView(tag(ctx, Lang.t("跨区", "Cross"), AMBER, 0x22E5A93B, 0x73E5A93B));
            } else {
                GeoTable.Point pt = Geo.point(ctx, region.iso, draftZone[0]);
                String city = pt != null && !pt.city.isEmpty() ? pt.city : region.dispName();
                linkBox.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
                linkBox.setBackground(box(ctx, 0x122EC4B6, 0x472EC4B6, 11));
                TextView t = mono(ctx,
                        Lang.t("同步坐标至 ", "Sync GPS → ") + city
                                + Lang.t(" · 重算 Wi-Fi/BLE", " · radio"), 10, ICE);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                linkBox.addView(t);
                View sw = miniSwitch(ctx, syncGps[0]);
                linkBox.addView(sw);
                linkBox.setOnClickListener(v -> {
                    syncGps[0] = !syncGps[0];
                    // Re-render toggle cleanly
                    linkBox.removeViewAt(linkBox.getChildCount() - 1);
                    linkBox.addView(miniSwitch(ctx, syncGps[0]));
                });
            }
        };

        final Runnable[] refreshLocales = new Runnable[1];
        refreshLocales[0] = () -> {
            locBar.removeAllViews();
            for (final String l : localeOptions) {
                boolean sel = l.equalsIgnoreCase(draftLocale[0]);
                TextView chip = filterChip(ctx, l, sel);
                chip.setOnClickListener(v -> {
                    draftLocale[0] = l;
                    refreshLocales[0].run();
                });
                locBar.addView(chip);
            }
        };
        refreshLocales[0].run();

        final BaseAdapter[] adapterRef = new BaseAdapter[1];
        adapterRef[0] = new BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int pos) { return filtered.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                final TimeZoneIndex.Entry e = filtered.get(pos);
                boolean sel = e.id.equals(draftZone[0]);
                boolean inReg = regionZoneSet.contains(e.id);
                LinearLayout row = optionRow(ctx, sel);
                row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));

                LinearLayout mid = new LinearLayout(ctx);
                mid.setOrientation(LinearLayout.VERTICAL);
                mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                TextView idTv = mono(ctx, e.id, 11.5f, FG);
                idTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                idTv.setSingleLine(true);
                idTv.setEllipsize(TextUtils.TruncateAt.END);
                mid.addView(idTv);
                GeoTable.Point pt = inReg ? Geo.point(ctx, region.iso, e.id) : null;
                String sub = e.utc + (pt != null && !pt.city.isEmpty() ? " · " + pt.city : "");
                TextView subTv = mono(ctx, sub, 9.5f, DIM);
                subTv.setSingleLine(true);
                mid.addView(subTv);
                row.addView(mid);

                if (sel) {
                    row.addView(tag(ctx, "✓", CYAN, 0x1F2EC4B6, 0x592EC4B6));
                } else if (inReg) {
                    row.addView(tag(ctx, isoUp, CYAN, 0x1F2EC4B6, 0x592EC4B6));
                } else {
                    row.addView(tag(ctx, e.utc, DIM, CARD, STROKE));
                }

                row.setOnClickListener(v -> {
                    draftZone[0] = e.id;
                    adapterRef[0].notifyDataSetChanged();
                    refreshLink.run();
                });
                return row;
            }
        };
        listView.setAdapter(adapterRef[0]);

        final Runnable populate = () -> {
            filtered.clear();
            String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (TimeZoneIndex.Entry e : regionZones) {
                if (q.isEmpty() || e.searchKey.contains(q)) {
                    filtered.add(e);
                }
            }
            for (TimeZoneIndex.Entry e : globalAll) {
                if (regionZoneSet.contains(e.id)) continue;
                if (q.isEmpty() || e.searchKey.contains(q)) {
                    filtered.add(e);
                }
            }
            adapterRef[0].notifyDataSetChanged();
        };
        populate.run();
        refreshLink.run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { populate.run(); }
        });

        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // 3. DEVICE & CSC WORKBENCH SHEET (默认: 整机属性 · 软重启 / 可选: 仅 Hook 零重启)
    // =========================================================================
    static void showDeviceCscSheet(final Context ctx, final Regions.R region,
                                   final String[][] presets, final String[] selfRow,
                                   final int currentPresetIdx, final boolean hasOverride,
                                   final boolean currentHookOnly, final String[] customRow,
                                   final List<CscOption> cscOptions, final String currentHwTag,
                                   final DeviceCallback callback) {
        if (ctx == null || selfRow == null || callback == null) return;

        // Index 0 = selfDevice, 1..N = presets[0..N-1], -1 = custom
        final int[] selectedIdx = new int[]{
                !hasOverride ? 0 : (customRow != null ? -1 : currentPresetIdx)};
        // User decision #1: default tier is System Properties + Soft Reboot (hookOnly = false).
        final boolean[] hookOnly = new boolean[]{hasOverride && currentHookOnly};
        final String[] activeBrand = new String[]{"ALL"};
        final String[] searchQuery = new String[]{""};
        final boolean[] cscOpen = new boolean[]{false};

        // Collect unique brand filter tabs
        final List<String> brands = new ArrayList<String>();
        brands.add("ALL");
        brands.add("SELF");
        for (String[] row : presets) {
            String b = brandDisplay(row[1], row[2]);
            if (!brands.contains(b)) brands.add(b);
        }
        brands.add("CUSTOM");

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx,
                Lang.t("机型与 CSC · ", "Device & CSC · ", "機種・CSC · ")
                        + (region != null ? Regions.flagOf(region.iso) + " " + region.dispName() : "—"),
                dialog));

        // Compact search input for fast filtering across 100+ certified presets
        final EditText searchInput = darkInput(ctx, "",
                Lang.t("搜索机型 / 型号 / 代号…", "Search model / code…", "機種・型番を検索…"));
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(-1, -2);
        silp.topMargin = dp(ctx, 10);
        sheet.addView(searchInput, silp);

        // Brand filter bar
        final LinearLayout brandBar = new LinearLayout(ctx);
        brandBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView bScroll = new HorizontalScrollView(ctx);
        bScroll.setHorizontalScrollBarEnabled(false);
        bScroll.addView(brandBar);
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(-1, -2);
        bslp.topMargin = dp(ctx, 8);
        bslp.bottomMargin = dp(ctx, 8);
        sheet.addView(bScroll, bslp);

        final LinearLayout listHost = new LinearLayout(ctx);
        listHost.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(listHost);
        int listMaxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.28f);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, listMaxH));

        final LinearLayout pinnedHost = new LinearLayout(ctx);
        pinnedHost.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(pinnedHost, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(ctx, 10), 0, 0);
        sheet.addView(foot);

        // Custom form fields persisted across brand switch
        final String[] baseCustom = customRow != null ? customRow : selfRow;
        final String hostRel = PersonaSynth.sdkForRelease(android.os.Build.VERSION.RELEASE) > 0
                ? android.os.Build.VERSION.RELEASE.trim()
                : PersonaSynth.releaseForSdk(android.os.Build.VERSION.SDK_INT);
        final String[] cBrand = new String[]{baseCustom[2]};
        final String[] cModel = new String[]{baseCustom[3]};
        final String[] cDev = new String[]{baseCustom[4]};
        final String[] cProd = new String[]{baseCustom[5]};
        final String[] cRel = new String[]{
                baseCustom[11] != null && PersonaSynth.sdkForRelease(baseCustom[11]) > 0
                        ? baseCustom[11].trim() : hostRel};

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            // Brand chips
            brandBar.removeAllViews();
            for (final String b : brands) {
                String label = "ALL".equals(b) ? Lang.t("全部", "All", "すべて")
                        : "SELF".equals(b) ? Lang.t("本机", "This", "実機")
                        : "CUSTOM".equals(b) ? Lang.t("自定义", "Custom", "カスタム")
                        : b;
                boolean sel = b.equals(activeBrand[0]);
                TextView chip = filterChip(ctx, label, sel);
                chip.setOnClickListener(v -> {
                    activeBrand[0] = b;
                    if ("CUSTOM".equals(b)) {
                        selectedIdx[0] = -1;
                    } else if ("SELF".equals(b)) {
                        selectedIdx[0] = 0;
                    }
                    render[0].run();
                });
                brandBar.addView(chip);
            }

            listHost.removeAllViews();
            pinnedHost.removeAllViews();
            foot.removeAllViews();
            searchInput.setVisibility("CUSTOM".equals(activeBrand[0]) ? View.GONE : View.VISIBLE);

            if ("CUSTOM".equals(activeBrand[0])) {
                // Inline custom model form
                LinearLayout form = new LinearLayout(ctx);
                form.setOrientation(LinearLayout.VERTICAL);
                form.setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9));
                form.setBackground(box(ctx, CARD2, STROKE, 12));

                form.addView(labeledInputRow(ctx, "brand", cBrand, "google", "model", cModel, "Pixel 9"));
                form.addView(labeledInputRow(ctx, "device", cDev, "tokay", "product", cProd, "tokay"));
                LinearLayout relBox = new LinearLayout(ctx);
                relBox.setOrientation(LinearLayout.VERTICAL);
                relBox.addView(mono(ctx, "Android release (13-17)", 9.5f, DIM));
                EditText relIn = darkInput(ctx, cRel[0], hostRel);
                relIn.addTextChangedListener(simpleWatcher(v -> cRel[0] = v));
                relBox.addView(relIn);
                form.addView(relBox);
                listHost.addView(form);
            } else {
                // Preset list
                LinearLayout list = new LinearLayout(ctx);
                list.setOrientation(LinearLayout.VERTICAL);
                String q = searchQuery[0] == null ? "" : searchQuery[0].trim().toLowerCase(Locale.ROOT);
                boolean selfMatches = q.isEmpty()
                        || (selfRow[3] != null && selfRow[3].toLowerCase(Locale.ROOT).contains(q))
                        || (selfRow[4] != null && selfRow[4].toLowerCase(Locale.ROOT).contains(q));
                if (("ALL".equals(activeBrand[0]) || "SELF".equals(activeBrand[0])) && selfMatches) {
                    list.addView(devicePresetRow(ctx, 0,
                            selfRow[3] + Lang.t(" (本机)", " (This)", " (実機)"),
                            shortFp(selfRow[8]) + " · Android " + selfRow[11] + " · SDK " + selfRow[12],
                            selectedIdx[0] == 0, () -> {
                                selectedIdx[0] = 0;
                                render[0].run();
                            }));
                }
                for (int i = 0; i < presets.length; i++) {
                    String[] p = presets[i];
                    String pb = brandDisplay(p[1], p[2]);
                    if (!"ALL".equals(activeBrand[0]) && !pb.equals(activeBrand[0])) continue;
                    if (!q.isEmpty()) {
                        boolean hit = p[0].toLowerCase(Locale.ROOT).contains(q)
                                || p[1].toLowerCase(Locale.ROOT).contains(q)
                                || p[3].toLowerCase(Locale.ROOT).contains(q)
                                || p[4].toLowerCase(Locale.ROOT).contains(q);
                        if (!hit) continue;
                    }
                    final int idx = i + 1;
                    list.addView(devicePresetRow(ctx, idx, p[0],
                            p[3] + " · Android " + p[11] + " · " + shortFp(p[8]),
                            selectedIdx[0] == idx, () -> {
                                selectedIdx[0] = idx;
                                render[0].run();
                            }));
                }
                if (list.getChildCount() == 0) {
                    TextView empty = txt(ctx, Lang.t("无匹配机型", "No matching devices", "一致なし"),
                            10.5f, MUTE, false);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(ctx, 18), 0, dp(ctx, 18));
                    list.addView(empty);
                }
                listHost.addView(list);
            }

            // Inline 4-field Diff Preview (pinned below list so always visible)
            String[] previewRow = selectedIdx[0] == 0 ? selfRow
                    : selectedIdx[0] > 0 && selectedIdx[0] <= presets.length
                            ? presets[selectedIdx[0] - 1]
                            : new String[]{cBrand[0] + " " + cModel[0], cBrand[0], cBrand[0],
                                    cModel[0], cDev[0], cProd[0], cDev[0], cDev[0],
                                    PersonaSynth.fingerprint(cBrand[0], cProd[0], cDev[0], cRel[0], "BP1A", "1000"),
                                    "", "", cRel[0], String.valueOf(Math.max(33, PersonaSynth.sdkForRelease(cRel[0]))), ""};
            String[] soc = PersonaSynth.socFor(previewRow[1], previewRow[3], previewRow[4],
                    previewRow[6], previewRow[7]);
            String cscTag = region != null ? Regions.cscFor(region.iso) : null;
            LinearLayout diff = new LinearLayout(ctx);
            diff.setOrientation(LinearLayout.VERTICAL);
            diff.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
            diff.setBackground(box(ctx, BG, STROKE, 12));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = dp(ctx, 8);
            diff.setLayoutParams(dlp);
            diff.addView(diffLine(ctx, "MODEL", previewRow[3], selectedIdx[0] == 0 ? FG : CYAN));
            diff.addView(diffLine(ctx, "SOC", soc[0] + " " + soc[1], FG));
            diff.addView(diffLine(ctx, "FP", shortFp(previewRow[8]), FG));
            diff.addView(diffLine(ctx, "CSC", cscTag != null ? cscTag : currentHwTag, DIM));
            pinnedHost.addView(diff);

            // Enforcement tier selector (Default: 整机属性 · 软重启)
            if (selectedIdx[0] != 0) {
                LinearLayout segBar = new LinearLayout(ctx);
                segBar.setOrientation(LinearLayout.HORIZONTAL);
                segBar.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
                segBar.setBackground(box(ctx, BG, STROKE, 12));
                LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-1, -2);
                sblp.topMargin = dp(ctx, 8);
                segBar.setLayoutParams(sblp);

                TextView sysSeg = segButton(ctx,
                        Lang.t("整机属性 · 软重启", "System · Soft Reboot", "システム · 再起動"),
                        !hookOnly[0], true);
                sysSeg.setOnClickListener(v -> {
                    if (hookOnly[0]) {
                        hookOnly[0] = false;
                        render[0].run();
                    }
                });
                TextView hookSeg = segButton(ctx,
                        Lang.t("仅 Hook 注入 · 零重启", "Hook Only · Instant", "Hook のみ · 即時"),
                        hookOnly[0], false);
                hookSeg.setOnClickListener(v -> {
                    if (!hookOnly[0]) {
                        hookOnly[0] = true;
                        render[0].run();
                    }
                });
                segBar.addView(sysSeg, new LinearLayout.LayoutParams(0, -2, 1f));
                segBar.addView(hookSeg, new LinearLayout.LayoutParams(0, -2, 1f));
                pinnedHost.addView(segBar);
            }

            // Collapsible CSC actions (if device exposes writable tags)
            if (cscOptions != null && !cscOptions.isEmpty()) {
                LinearLayout cscRow = optionRow(ctx, cscOpen[0]);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.topMargin = dp(ctx, 8);
                clp.bottomMargin = dp(ctx, 2);
                cscRow.setLayoutParams(clp);
                TextView cl = txt(ctx, Lang.t("区域标记 / CSC · ", "Region tag / CSC · ")
                        + currentHwTag, 10.5f, cscOpen[0] ? FG : DIM, false);
                cl.setSingleLine(true);
                cl.setEllipsize(TextUtils.TruncateAt.END);
                cl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                cscRow.addView(cl);
                cscRow.setOnClickListener(v -> {
                    cscOpen[0] = !cscOpen[0];
                    render[0].run();
                });
                pinnedHost.addView(cscRow);

                if (cscOpen[0]) {
                    for (final CscOption opt : cscOptions) {
                        LinearLayout or = optionRow(ctx, false);
                        TextView ot = txt(ctx, opt.label, 11, FG, false);
                        ot.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                        or.addView(ot);
                        or.setOnClickListener(v -> {
                            dialog.dismiss();
                            callback.onRunCscScript(opt.target, opt.script);
                        });
                        pinnedHost.addView(or);
                    }
                }
            }

            // Footer actions
            if (selectedIdx[0] != 0) {
                TextView restore = secondaryBtn(ctx, Lang.t("还原", "Reset", "戻す"));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.rightMargin = dp(ctx, 8);
                restore.setLayoutParams(rlp);
                restore.setOnClickListener(v -> {
                    selectedIdx[0] = 0;
                    activeBrand[0] = "ALL";
                    render[0].run();
                });
                foot.addView(restore);
            }

            boolean willReboot = (selectedIdx[0] == 0 && hasOverride && !currentHookOnly)
                    || (selectedIdx[0] != 0 && !hookOnly[0]);
            String actionLabel;
            if (selectedIdx[0] == 0) {
                actionLabel = willReboot
                        ? Lang.t("还原并软重启", "Restore & Soft Reboot", "復元して再起動")
                        : Lang.t("还原为本机", "Restore Native", "実機に戻す");
            } else {
                actionLabel = willReboot
                        ? Lang.t("写入并软重启", "Write & Soft Reboot", "書込み・再起動")
                        : Lang.t("应用 Hook 机型", "Apply Hook Model", "Hook 機種を適用");
            }

            TextView save = primaryBtn(ctx, actionLabel, willReboot);
            save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            save.setOnClickListener(v -> {
                if (selectedIdx[0] == -1) {
                    String b = cBrand[0].trim();
                    String m = cModel[0].trim();
                    String d = cDev[0].trim();
                    String p = cProd[0].trim();
                    String rel = cRel[0].trim();
                    if (b.isEmpty() || m.isEmpty() || d.isEmpty() || p.isEmpty()) {
                        Toast.makeText(ctx, Lang.t("brand / model / device / product 均必填",
                                "brand / model / device / product required"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!b.matches("[A-Za-z0-9._-]{1,92}")
                            || !d.matches("[A-Za-z0-9._-]{1,92}")
                            || !p.matches("[A-Za-z0-9._-]{1,92}")
                            || m.length() > 64) {
                        Toast.makeText(ctx, Lang.t("自定义字段含非法字符",
                                "Invalid characters in custom device fields"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int sdk = PersonaSynth.sdkForRelease(rel);
                    if (sdk < 0) {
                        Toast.makeText(ctx, Lang.t("Android 版本仅支持 13–17",
                                "Android release must be 13-17"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.util.Random rng = new java.util.Random();
                    String[] idAndPatch = PersonaSynth.buildIdAndPatchForSdk(sdk, rng);
                    String inc = String.valueOf(10000000 + rng.nextInt(90000000));
                    String[] row = {
                            b + " " + m, b, b, m, d, p, d, d,
                            PersonaSynth.fingerprint(b, p, d, rel, idAndPatch[0], inc),
                            idAndPatch[0], inc, rel, String.valueOf(sdk), idAndPatch[1]
                    };
                    dialog.dismiss();
                    callback.onSelectCustom(row, rng.nextLong(), hookOnly[0]);
                } else {
                    dialog.dismiss();
                    callback.onSelectPreset(selectedIdx[0], selectedIdx[0] != 0 && hookOnly[0]);
                }
            });
            foot.addView(save);
        };
        searchInput.addTextChangedListener(simpleWatcher(v -> {
            searchQuery[0] = v;
            render[0].run();
        }));
        render[0].run();
        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // 4. ANDROID ID SHEET (4x4 分段预览 + 🎲 一键随机生成 + 16/16 实时校验)
    // =========================================================================
    static void showAidSheet(final Context ctx, final String nativeAid,
                             final String currentOverride, final AidCallback callback) {
        if (ctx == null || callback == null) return;
        final String safeNative = nativeAid == null ? "" : nativeAid.toLowerCase(Locale.ROOT);
        final String[] draft = new String[]{
                currentOverride != null ? currentOverride.toLowerCase(Locale.ROOT) : safeNative};

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, "Android ID", dialog));

        // 4x4 chunked preview
        final LinearLayout chunksRow = new LinearLayout(ctx);
        chunksRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(-1, -2);
        crlp.topMargin = dp(ctx, 10);
        crlp.bottomMargin = dp(ctx, 10);
        sheet.addView(chunksRow, crlp);

        // Status + live counter row
        LinearLayout lblRow = new LinearLayout(ctx);
        lblRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView stateTv = mono(ctx, "", 10, DIM);
        stateTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView countTv = mono(ctx, "", 10, GREEN);
        lblRow.addView(stateTv);
        lblRow.addView(countTv);
        sheet.addView(lblRow);

        // Input
        final EditText input = darkInput(ctx, draft[0], safeNative);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(ctx, 5);
        ilp.bottomMargin = dp(ctx, 10);
        sheet.addView(input, ilp);

        // Quick action row: Random + Native
        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView randBtn = secondaryBtn(ctx, Lang.t("🎲 随机生成", "🎲 Random", "🎲 ランダム"));
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(0, -2, 1f);
        rblp.rightMargin = dp(ctx, 8);
        randBtn.setLayoutParams(rblp);

        TextView nativeBtn = secondaryBtn(ctx, Lang.t("恢复本机值", "Use Device ID", "実機値を使う"));
        nativeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        actRow.addView(randBtn);
        actRow.addView(nativeBtn);
        sheet.addView(actRow);

        // Footer
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(ctx, 12), 0, 0);
        final TextView saveBtn = primaryBtn(ctx, Lang.t("保存并发布", "Save & Publish", "保存して配信"), false);
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        foot.addView(saveBtn);
        sheet.addView(foot);

        final Runnable refresh = () -> {
            String v = draft[0];
            boolean ok = PersonaEditRules.validAid(v);
            boolean isNative = v.equals(safeNative);

            chunksRow.removeAllViews();
            String padded = v;
            while (padded.length() < 16) padded += "·";
            for (int i = 0; i < 4; i++) {
                TextView ch = mono(ctx, padded.substring(i * 4, i * 4 + 4), 13.5f,
                        isNative ? FG : (ok ? CYAN : AMBER));
                ch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                ch.setGravity(Gravity.CENTER);
                ch.setPadding(dp(ctx, 4), dp(ctx, 9), dp(ctx, 4), dp(ctx, 9));
                ch.setBackground(box(ctx, BG, isNative ? STROKE : (ok ? STROKE2 : RED), 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (i < 3) lp.rightMargin = dp(ctx, 6);
                chunksRow.addView(ch, lp);
            }

            stateTv.setText(isNative
                    ? Lang.t("本机原值", "Device native", "実機値")
                    : Lang.t("Hook 覆写 · 下次启动", "Hook override · next launch", "Hook 上書き · 次回起動"));
            stateTv.setTextColor(isNative ? DIM : CYAN);
            countTv.setText(v.length() + "/16 hex");
            countTv.setTextColor(ok ? GREEN : RED);
            saveBtn.setEnabled(ok);
            saveBtn.setAlpha(ok ? 1f : 0.4f);
        };
        refresh.run();

        final boolean[] programmatic = new boolean[]{false};
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (programmatic[0]) return;
                draft[0] = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh.run();
            }
        });

        randBtn.setOnClickListener(v -> {
            String next = PersonaEditRules.randomAid(SECURE_RNG);
            draft[0] = next;
            programmatic[0] = true;
            input.setText(next);
            input.setSelection(next.length());
            programmatic[0] = false;
            refresh.run();
        });
        nativeBtn.setOnClickListener(v -> {
            draft[0] = safeNative;
            programmatic[0] = true;
            input.setText(safeNative);
            input.setSelection(safeNative.length());
            programmatic[0] = false;
            refresh.run();
        });
        saveBtn.setOnClickListener(v -> {
            if (!PersonaEditRules.validAid(draft[0])) return;
            dialog.dismiss();
            callback.onSave(draft[0].equals(safeNative) ? null : draft[0]);
        });

        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // 5. TARGET-LIVE GUARD SHEET (切区前目标存活保护)
    // =========================================================================
    static void showTargetRunningGuard(final Context ctx, final String targetName,
                                       final String nextRegionLabel,
                                       final Runnable onStopAndProceed,
                                       final Runnable onProceedAnyway) {
        if (ctx == null) return;
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx,
                Lang.t("目标正在运行 · ", "Target Running · ", "対象が実行中 · ") + targetName,
                dialog));

        TextView warn = linkStrip(ctx,
                "⚠ " + targetName + Lang.t(" 存活中 · 切至 ", " active · switching to ")
                        + nextRegionLabel + Lang.t(" 易触发同会话跨区", " risks mid-session drift"),
                true);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(ctx, 10);
        wlp.bottomMargin = dp(ctx, 12);
        warn.setLayoutParams(wlp);
        sheet.addView(warn);

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);

        TextView ignore = secondaryBtn(ctx, Lang.t("忽略继续", "Ignore", "そのまま続行"));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.rightMargin = dp(ctx, 8);
        ignore.setLayoutParams(ilp);
        ignore.setOnClickListener(v -> {
            dialog.dismiss();
            if (onProceedAnyway != null) onProceedAnyway.run();
        });
        foot.addView(ignore);

        TextView stop = primaryBtn(ctx, Lang.t("停止进程并继续", "Force-Stop & Continue", "停止して続行"), true);
        stop.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        stop.setOnClickListener(v -> {
            dialog.dismiss();
            if (onStopAndProceed != null) onStopAndProceed.run();
        });
        foot.addView(stop);
        sheet.addView(foot);

        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHEET 6: UNIFIED CONFIRMATION SHEET (replaces legacy AlertDialogs)
    // =========================================================================
    static void showConfirmSheet(Context ctx, String title, String detail,
                                 String confirmText, boolean danger, Runnable onConfirm) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, title, dialog));

        if (detail != null && !detail.isEmpty()) {
            TextView body = txt(ctx, detail, 11.5f, DIM, false);
            body.setLineSpacing(dp(ctx, 2), 1.0f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = dp(ctx, 8);
            blp.bottomMargin = dp(ctx, 14);
            body.setLayoutParams(blp);
            sheet.addView(body);
        }

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = secondaryBtn(ctx, Lang.t("取消", "Cancel", "キャンセル"));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.rightMargin = dp(ctx, 8);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        foot.addView(cancel);

        TextView ok = primaryBtn(ctx, confirmText, danger);
        ok.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        foot.addView(ok);
        sheet.addView(foot);

        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHEET 7: COUNTRY CODE HINT EDITOR (replaces legacy AlertDialog)
    // =========================================================================
    static void showCountryHintSheet(Context ctx, String currentVal,
                                     Consumer<String> onSave, Runnable onRestore) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, "country_code_hint", dialog));

        EditText input = darkInput(ctx, currentVal == null ? "" : currentVal, "US / GB / JP");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(ctx, 10);
        ilp.bottomMargin = dp(ctx, 12);
        input.setLayoutParams(ilp);
        sheet.addView(input);

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);

        TextView restore = secondaryBtn(ctx, Lang.t("恢复", "Restore", "復元"));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.rightMargin = dp(ctx, 8);
        restore.setLayoutParams(rlp);
        restore.setOnClickListener(v -> {
            dialog.dismiss();
            if (onRestore != null) onRestore.run();
        });
        foot.addView(restore);

        TextView save = primaryBtn(ctx, Lang.t("保存", "Save", "保存"), false);
        save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        save.setOnClickListener(v -> {
            String iso = input.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!iso.matches("[A-Z]{2}")) {
                Toast.makeText(ctx, "ISO-2 (US / GB / JP)", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            if (onSave != null) onSave.accept(iso);
        });
        foot.addView(save);
        sheet.addView(foot);

        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHEET 8: WI-FI NEIGHBOURHOOD EDITOR
    // =========================================================================
    interface WifiSaveCallback {
        void onSave(boolean enabled, String customSsid, String customBssid, long newSalt);
    }

    static void showWifiEditorSheet(
            Context ctx,
            boolean initialEnabled,
            String initialSsid,
            String initialBssid,
            long initialSalt,
            java.util.function.Function<Long, List<WifiProfile.Ap>> apPreviewBuilder,
            WifiSaveCallback onSave) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final boolean[] enabled = {initialEnabled};
        final String[] ssid = {initialSsid == null ? "" : initialSsid};
        final String[] bssid = {initialBssid == null ? "" : initialBssid};
        final long[] salt = {initialSalt};

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, Lang.t("Wi-Fi 邻域", "Wi-Fi Neighbourhood", "Wi-Fi 環境"), dialog));

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(body);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();

            // Enable toggle row
            LinearLayout toggle = optionRow(ctx, enabled[0]);
            TextView tl = txt(ctx, Lang.t("注入虚拟 Wi-Fi 邻域", "Virtual Wi-Fi neighbourhood",
                    "仮想 Wi-Fi 環境を注入"), 12, FG, true);
            tl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(tl);
            toggle.addView(miniSwitch(ctx, enabled[0]));
            toggle.setOnClickListener(v -> {
                enabled[0] = !enabled[0];
                render[0].run();
            });
            body.addView(toggle);

            if (enabled[0]) {
                List<WifiProfile.Ap> baseAps = apPreviewBuilder.apply(salt[0]);
                List<WifiProfile.Ap> effAps = WifiProfile.applyOverrides(baseAps, ssid[0], bssid[0]);
                WifiProfile.Ap primary = effAps.isEmpty() ? null : effAps.get(0);

                // Custom connected SSID + BSSID inputs
                TextView ssidLbl = mono(ctx, Lang.t("当前连接 SSID（留空自动）", "Connected SSID (blank=auto)"),
                        9.5f, DIM);
                ssidLbl.setPadding(0, dp(ctx, 4), 0, dp(ctx, 3));
                body.addView(ssidLbl);
                EditText ssidEt = darkInput(ctx, ssid[0], primary != null ? primary.ssid : "Home-5G");
                ssidEt.addTextChangedListener(simpleWatcher(s -> ssid[0] = s));
                body.addView(ssidEt);

                LinearLayout macRow = new LinearLayout(ctx);
                macRow.setOrientation(LinearLayout.HORIZONTAL);
                macRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
                mlp.topMargin = dp(ctx, 8);
                macRow.setLayoutParams(mlp);

                EditText bssidEt = darkInput(ctx, bssid[0], primary != null ? primary.bssid : "3C:84:6A:12:34:56");
                bssidEt.addTextChangedListener(simpleWatcher(s -> bssid[0] = s));
                bssidEt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                macRow.addView(bssidEt);

                TextView genMac = secondaryBtn(ctx, Lang.t("随机 MAC", "Rand MAC", "MAC生成"));
                genMac.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
                glp.leftMargin = dp(ctx, 6);
                genMac.setLayoutParams(glp);
                genMac.setOnClickListener(v -> {
                    bssid[0] = WifiProfile.randomMac(System.nanoTime());
                    render[0].run();
                });
                macRow.addView(genMac);
                body.addView(macRow);

                // Synthesized AP preview box
                LinearLayout previewHead = new LinearLayout(ctx);
                previewHead.setOrientation(LinearLayout.HORIZONTAL);
                previewHead.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams phlp = new LinearLayout.LayoutParams(-1, -2);
                phlp.topMargin = dp(ctx, 10);
                phlp.bottomMargin = dp(ctx, 4);
                previewHead.setLayoutParams(phlp);

                TextView pLbl = mono(ctx, Lang.t("周边邻域 · ", "Nearby APs · ") + effAps.size(), 9.5f, DIM);
                pLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                previewHead.addView(pLbl);

                TextView reroll = mono(ctx, Lang.t("重随机", "Reroll", "再生成"), 10, CYAN);
                reroll.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                reroll.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
                reroll.setBackground(box(ctx, 0x1F2EC4B6, 0x662EC4B6, 9));
                bindPress(reroll);
                reroll.setOnClickListener(v -> {
                    salt[0] = salt[0] + 1L;
                    render[0].run();
                });
                previewHead.addView(reroll);
                body.addView(previewHead);

                ScrollView sv = new ScrollView(ctx);
                int maxH = Math.min(dp(ctx, 150),
                        (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.22f));
                sv.setLayoutParams(new LinearLayout.LayoutParams(-1, maxH));
                LinearLayout list = new LinearLayout(ctx);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
                list.setBackground(box(ctx, CARD2, STROKE, 12));
                for (int i = 0; i < effAps.size(); i++) {
                    WifiProfile.Ap ap = effAps.get(i);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
                    TextView sTv = mono(ctx, ap.ssid, 10, ap.connected ? CYAN : FG);
                    sTv.setSingleLine(true);
                    sTv.setEllipsize(TextUtils.TruncateAt.END);
                    sTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(sTv);
                    String band = ap.frequency >= 5900 ? "6G" : ap.frequency >= 5000 ? "5G" : "2.4G";
                    TextView mTv = mono(ctx, band + " · " + ap.level + "dBm", 9, MUTE);
                    row.addView(mTv);
                    list.addView(row);
                }
                sv.addView(list);
                body.addView(sv);
            }

            // Footer
            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.topMargin = dp(ctx, 12);
            foot.setLayoutParams(flp);

            TextView reset = secondaryBtn(ctx, Lang.t("恢复默认", "Reset", "初期化"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.rightMargin = dp(ctx, 8);
            reset.setLayoutParams(rlp);
            reset.setOnClickListener(v -> {
                enabled[0] = true;
                ssid[0] = "";
                bssid[0] = "";
                salt[0] = 0L;
                render[0].run();
            });
            foot.addView(reset);

            TextView save = primaryBtn(ctx, Lang.t("保存并应用", "Save", "保存"), false);
            save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            save.setOnClickListener(v -> {
                String cleanBssid = bssid[0].trim();
                if (!cleanBssid.isEmpty() && !WifiProfile.isValidMac(cleanBssid)) {
                    Toast.makeText(ctx, Lang.t("BSSID 格式需为 XX:XX:XX:XX:XX:XX", "Invalid MAC"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                if (onSave != null) {
                    onSave.onSave(enabled[0], ssid[0].trim(), cleanBssid, salt[0]);
                }
            });
            foot.addView(save);
            body.addView(foot);
        };
        render[0].run();
        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHEET 9: BLE BLUETOOTH NEIGHBOURHOOD EDITOR
    // =========================================================================
    interface BleSaveCallback {
        void onSave(boolean enabled, boolean emptyMode, String customBonded, long newSalt);
    }

    static void showBleEditorSheet(
            Context ctx,
            boolean initialEnabled,
            boolean initialEmptyMode,
            String initialBonded,
            long initialSalt,
            java.util.function.Function<Long, List<BleProfile.Device>> blePreviewBuilder,
            BleSaveCallback onSave) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final boolean[] enabled = {initialEnabled};
        final boolean[] emptyMode = {initialEmptyMode};
        final String[] bonded = {initialBonded == null ? "" : initialBonded};
        final long[] salt = {initialSalt};

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, Lang.t("蓝牙 BLE 邻域", "BLE Neighbourhood", "Bluetooth 環境"), dialog));

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(body);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();

            // 3-way mode segmented toggle: Spoof | Empty Clean | Off
            LinearLayout seg = new LinearLayout(ctx);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            seg.setBackground(box(ctx, BG, STROKE, 12));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.bottomMargin = dp(ctx, 10);
            seg.setLayoutParams(slp);

            boolean isSpoof = enabled[0] && !emptyMode[0];
            boolean isEmpty = enabled[0] && emptyMode[0];
            boolean isOff = !enabled[0];

            TextView m1 = segButton(ctx, Lang.t("虚拟邻域", "Virtual", "仮想"), isSpoof, false);
            m1.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            m1.setOnClickListener(v -> { enabled[0] = true; emptyMode[0] = false; render[0].run(); });

            TextView m2 = segButton(ctx, Lang.t("空蓝牙洁净", "Empty", "空モード"), isEmpty, false);
            m2.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            m2.setOnClickListener(v -> { enabled[0] = true; emptyMode[0] = true; render[0].run(); });

            TextView m3 = segButton(ctx, Lang.t("关闭", "Off", "オフ"), isOff, true);
            m3.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            m3.setOnClickListener(v -> { enabled[0] = false; emptyMode[0] = false; render[0].run(); });

            seg.addView(m1);
            seg.addView(m2);
            seg.addView(m3);
            body.addView(seg);

            if (isSpoof) {
                List<BleProfile.Device> baseDevs = blePreviewBuilder.apply(salt[0]);
                List<BleProfile.Device> effDevs = BleProfile.applyOverrides(baseDevs, bonded[0], false);
                BleProfile.Device first = effDevs.isEmpty() ? null : effDevs.get(0);

                TextView bLbl = mono(ctx, Lang.t("已配对设备名（留空自动）", "Bonded device name (blank=auto)"),
                        9.5f, DIM);
                bLbl.setPadding(0, dp(ctx, 2), 0, dp(ctx, 3));
                body.addView(bLbl);
                EditText bEt = darkInput(ctx, bonded[0], first != null ? first.name : "AirPods Pro");
                bEt.addTextChangedListener(simpleWatcher(s -> bonded[0] = s));
                body.addView(bEt);

                LinearLayout previewHead = new LinearLayout(ctx);
                previewHead.setOrientation(LinearLayout.HORIZONTAL);
                previewHead.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams phlp = new LinearLayout.LayoutParams(-1, -2);
                phlp.topMargin = dp(ctx, 10);
                phlp.bottomMargin = dp(ctx, 4);
                previewHead.setLayoutParams(phlp);

                TextView pLbl = mono(ctx, Lang.t("虚拟设备 · ", "Virtual BLE · ") + effDevs.size(), 9.5f, DIM);
                pLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                previewHead.addView(pLbl);

                TextView reroll = mono(ctx, Lang.t("重随机", "Reroll", "再生成"), 10, CYAN);
                reroll.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                reroll.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
                reroll.setBackground(box(ctx, 0x1F2EC4B6, 0x662EC4B6, 9));
                bindPress(reroll);
                reroll.setOnClickListener(v -> {
                    salt[0] = salt[0] + 1L;
                    render[0].run();
                });
                previewHead.addView(reroll);
                body.addView(previewHead);

                LinearLayout list = new LinearLayout(ctx);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
                list.setBackground(box(ctx, CARD2, STROKE, 12));
                for (BleProfile.Device dev : effDevs) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
                    TextView nTv = mono(ctx, dev.name, 10, dev.bonded ? CYAN : FG);
                    nTv.setSingleLine(true);
                    nTv.setEllipsize(TextUtils.TruncateAt.END);
                    nTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    row.addView(nTv);
                    TextView mTv = mono(ctx, dev.address.substring(0, 8) + " · " + dev.rssi + "dBm", 9, MUTE);
                    row.addView(mTv);
                    list.addView(row);
                }
                body.addView(list);
            }

            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.topMargin = dp(ctx, 12);
            foot.setLayoutParams(flp);

            TextView reset = secondaryBtn(ctx, Lang.t("恢复默认", "Reset", "初期化"));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.rightMargin = dp(ctx, 8);
            reset.setLayoutParams(rlp);
            reset.setOnClickListener(v -> {
                enabled[0] = true;
                emptyMode[0] = false;
                bonded[0] = "";
                salt[0] = 0L;
                render[0].run();
            });
            foot.addView(reset);

            TextView save = primaryBtn(ctx, Lang.t("保存并应用", "Save", "保存"), false);
            save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            save.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSave != null) {
                    onSave.onSave(enabled[0], emptyMode[0], bonded[0].trim(), salt[0]);
                }
            });
            foot.addView(save);
            body.addView(foot);
        };
        render[0].run();
        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHEET 10: ENVIRONMENTAL SENSOR PRESET EDITOR
    // =========================================================================
    interface SensorSaveCallback {
        void onSave(boolean enabled, String preset);
    }

    static void showSensorEditorSheet(
            Context ctx,
            boolean initialEnabled,
            String initialPreset,
            java.util.function.Function<String, SensorProfile> previewBuilder,
            SensorSaveCallback onSave) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final boolean[] enabled = {initialEnabled};
        final String[] preset = {initialPreset == null || initialPreset.isEmpty()
                ? SensorProfile.PRESET_AUTO : initialPreset};

        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, Lang.t("环境传感器基线", "Sensor Baseline", "センサー基準"), dialog));

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(body);

        final String[][] presets = {
                {SensorProfile.PRESET_AUTO, Lang.t("自动昼夜", "Auto day/night", "自動"),
                        Lang.t("随坐标时区自动匹配光照、气压与地磁", "Follows local hour, altitude & declination")},
                {SensorProfile.PRESET_DESK, Lang.t("桌面静止", "Desk resting", "卓上静止"),
                        Lang.t("极低噪声 · 260 lux 室内光", "Low noise · 260 lux indoor")},
                {SensorProfile.PRESET_HANDHELD, Lang.t("手持阅读", "Handheld tilt", "手持ち"),
                        Lang.t("自然握持倾角 · 微幅陀螺漂移", "Natural reading tilt & micro-drift")},
                {SensorProfile.PRESET_INDOOR_NIGHT, Lang.t("夜间室内", "Indoor night", "夜間室内"),
                        Lang.t("低照度 ~12 lux · 静止基线", "Dim ~12 lux · resting baseline")},
                {SensorProfile.PRESET_OUTDOOR_DAY, Lang.t("户外日间", "Outdoor daylight", "屋外日中"),
                        Lang.t("高照度 ~4800 lux · 气压随海拔", "Bright ~4800 lux · altitude pressure")},
        };

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();

            LinearLayout toggle = optionRow(ctx, enabled[0]);
            TextView tl = txt(ctx, Lang.t("启用物理一致传感器基线", "Coherent sensor baseline",
                    "物理整合センサー基準を有効化"), 12, FG, true);
            tl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            toggle.addView(tl);
            toggle.addView(miniSwitch(ctx, enabled[0]));
            toggle.setOnClickListener(v -> {
                enabled[0] = !enabled[0];
                render[0].run();
            });
            body.addView(toggle);

            if (enabled[0]) {
                for (final String[] p : presets) {
                    boolean sel = p[0].equals(preset[0]);
                    LinearLayout row = optionRow(ctx, sel);
                    LinearLayout col = new LinearLayout(ctx);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                    col.addView(txt(ctx, p[1], 11.5f, sel ? FG : DIM, sel));
                    col.addView(mono(ctx, p[2], 9.5f, MUTE));
                    row.addView(col);
                    if (sel) {
                        row.addView(tag(ctx, "✓", CYAN, 0x1F2EC4B6, 0x592EC4B6));
                    }
                    row.setOnClickListener(v -> {
                        preset[0] = p[0];
                        render[0].run();
                    });
                    body.addView(row);
                }

                // Live physical telemetry readout (read-only verification of gravity/mag/light/baro)
                SensorProfile sp = previewBuilder.apply(preset[0]);
                if (sp != null) {
                    double gMag = Math.sqrt(
                            sp.accelerometer[0] * sp.accelerometer[0]
                                    + sp.accelerometer[1] * sp.accelerometer[1]
                                    + sp.accelerometer[2] * sp.accelerometer[2]);
                    String summary = String.format(Locale.US,
                            "|g|=%.2f  %.0f lux  %.1f hPa  B=(%.0f,%.0f,%.0f)µT",
                            gMag, sp.light, sp.pressure,
                            sp.magnetometer[0], sp.magnetometer[1], sp.magnetometer[2]);
                    TextView strip = linkStrip(ctx, summary, false);
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
                    slp.topMargin = dp(ctx, 4);
                    strip.setLayoutParams(slp);
                    body.addView(strip);
                }
            }

            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.topMargin = dp(ctx, 12);
            foot.setLayoutParams(flp);

            TextView cancel = secondaryBtn(ctx, Lang.t("取消", "Cancel", "キャンセル"));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.rightMargin = dp(ctx, 8);
            cancel.setLayoutParams(clp);
            cancel.setOnClickListener(v -> dialog.dismiss());
            foot.addView(cancel);

            TextView save = primaryBtn(ctx, Lang.t("保存并应用", "Save", "保存"), false);
            save.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            save.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSave != null) {
                    onSave.onSave(enabled[0], preset[0]);
                }
            });
            foot.addView(save);
            body.addView(foot);
        };
        render[0].run();
        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // PERSISTENT IPV6 ENTRY SWITCH SHEET
    // =========================================================================
    static void showIpv6ModeSheet(Context ctx, boolean currentSpoofEnabled, String regionIso,
                                  String currentPrefix, java.util.function.Consumer<Boolean> onSelect) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = sheetRoot(ctx);
        sheet.addView(sheetHandle(ctx));
        sheet.addView(sheetHeader(ctx, Lang.t("IPv6 模式", "IPv6 Mode", "IPv6 モード"), dialog));

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(ctx, 12);
        list.setLayoutParams(llp);

        // Option 1: Public IPv6 Off (default when region active; exempts IMS/VoWiFi)
        LinearLayout offRow = optionRow(ctx, !currentSpoofEnabled);
        LinearLayout offMid = new LinearLayout(ctx);
        offMid.setOrientation(LinearLayout.VERTICAL);
        offMid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        offMid.addView(txt(ctx, Lang.t("禁用公网 IPv6", "Disable Public IPv6", "公網 IPv6 無効"), 12.5f, FG, true));
        offMid.addView(mono(ctx, Lang.t("IMS / VoWiFi 豁免 · 地区关闭自动恢复",
                "IMS/VoWiFi exempt · restores when region off",
                "IMS/VoWiFi 除外 · 地域オフで復元"), 9.5f, DIM));
        offRow.addView(offMid);
        offRow.addView(tag(ctx, !currentSpoofEnabled ? "✓" : Lang.t("默认", "Default", "既定"),
                !currentSpoofEnabled ? GREEN : DIM,
                !currentSpoofEnabled ? 0x1F34D399 : CARD,
                !currentSpoofEnabled ? 0x5934D399 : STROKE));
        offRow.setOnClickListener(v -> {
            dialog.dismiss();
            if (currentSpoofEnabled && onSelect != null) {
                onSelect.accept(Boolean.FALSE);
            }
        });
        list.addView(offRow);

        // Option 2: Spoofed regional IPv6
        String isoTag = regionIso == null ? "" : regionIso.trim().toUpperCase(Locale.ROOT);
        String spoofMeta = currentPrefix != null && !currentPrefix.isEmpty()
                ? currentPrefix + (isoTag.isEmpty() ? "" : " · " + isoTag)
                : Lang.t("按当前地区/运营商分配伪装前缀",
                        "Assign regional/carrier spoof prefix",
                        "地域/キャリア別プレフィックス割当");
        LinearLayout onRow = optionRow(ctx, currentSpoofEnabled);
        LinearLayout onMid = new LinearLayout(ctx);
        onMid.setOrientation(LinearLayout.VERTICAL);
        onMid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        onMid.addView(txt(ctx, Lang.t("开启伪装 IPv6", "Spoofed IPv6", "IPv6 偽装オン"), 12.5f, FG, true));
        TextView onMt = mono(ctx, spoofMeta, 9.5f, DIM);
        onMt.setSingleLine(true);
        onMt.setEllipsize(TextUtils.TruncateAt.END);
        onMid.addView(onMt);
        onRow.addView(onMid);
        onRow.addView(tag(ctx, currentSpoofEnabled ? "✓" : "v6",
                currentSpoofEnabled ? CYAN : DIM,
                currentSpoofEnabled ? 0x1F2EC4B6 : CARD,
                currentSpoofEnabled ? 0x592EC4B6 : STROKE));
        onRow.setOnClickListener(v -> {
            dialog.dismiss();
            if (!currentSpoofEnabled && onSelect != null) {
                onSelect.accept(Boolean.TRUE);
            }
        });
        list.addView(onRow);

        sheet.addView(list);
        presentBottomSheet(ctx, dialog, sheet);
    }

    // =========================================================================
    // SHARED BOTTOM-SHEET PRIMITIVES
    // =========================================================================
    private static LinearLayout sheetRoot(Context ctx) {
        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 18));
        return sheet;
    }

    private static View sheetHandle(Context ctx) {
        View h = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(STROKE2);
        bg.setCornerRadius(dp(ctx, 2));
        h.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(ctx, 11);
        h.setLayoutParams(lp);
        return h;
    }

    private static LinearLayout sheetHeader(Context ctx, String title, Dialog dialog) {
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = txt(ctx, title, 14.5f, FG, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(t);
        TextView close = txt(ctx, "✕", 12, DIM, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(ctx, CARD2, STROKE, 14));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        return head;
    }

    private static void presentBottomSheet(Context ctx, Dialog dialog, View content) {
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            float r = dp(ctx, 22);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            bg.setStroke(dp(ctx, 1), STROKE2);
            w.setBackgroundDrawable(bg);
            w.setDimAmount(0.68f);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.84f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            content.addOnLayoutChangeListener((v, l, t, ri, b, ol, ot, ori, ob) -> {
                if (b - t > maxH) {
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
                }
            });
        }
        dialog.show();
    }

    private static LinearLayout optionRow(Context ctx, boolean selected) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9));
        row.setBackground(box(ctx, selected ? 0x152EC4B6 : CARD2, selected ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(ctx, 6);
        row.setLayoutParams(lp);
        bindPress(row);
        return row;
    }

    private static LinearLayout devicePresetRow(Context ctx, int idx, String title, String meta,
                                                boolean selected, Runnable onClick) {
        LinearLayout row = optionRow(ctx, selected);
        row.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = txt(ctx, title, 12, FG, true);
        t.setSingleLine(true);
        mid.addView(t);
        TextView m = mono(ctx, meta, 9.5f, DIM);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(m);
        row.addView(mid);
        if (selected) {
            row.addView(tag(ctx, "✓", CYAN, 0x1F2EC4B6, 0x592EC4B6));
        }
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static LinearLayout diffLine(Context ctx, String key, String val, int valColor) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        TextView k = mono(ctx, key, 10, MUTE);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 56), -2));
        TextView v = mono(ctx, val == null ? "—" : val, 10, valColor);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(k);
        r.addView(v);
        return r;
    }

    private static LinearLayout labeledInputRow(Context ctx,
                                                String k1, String[] v1, String h1,
                                                String k2, String[] v2, String h2) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(ctx, 6);
        row.setLayoutParams(rlp);

        LinearLayout b1 = new LinearLayout(ctx);
        b1.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp1.rightMargin = dp(ctx, 8);
        b1.setLayoutParams(lp1);
        b1.addView(mono(ctx, k1, 9.5f, DIM));
        EditText e1 = darkInput(ctx, v1[0], h1);
        e1.addTextChangedListener(simpleWatcher(s -> v1[0] = s));
        b1.addView(e1);

        LinearLayout b2 = new LinearLayout(ctx);
        b2.setOrientation(LinearLayout.VERTICAL);
        b2.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        b2.addView(mono(ctx, k2, 9.5f, DIM));
        EditText e2 = darkInput(ctx, v2[0], h2);
        e2.addTextChangedListener(simpleWatcher(s -> v2[0] = s));
        b2.addView(e2);

        row.addView(b1);
        row.addView(b2);
        return row;
    }

    private static TextView segButton(Context ctx, String text, boolean active, boolean danger) {
        int fg = active ? (danger ? RED : FG) : DIM;
        int fill = active ? (danger ? 0x24E55353 : CARD2) : Color.TRANSPARENT;
        int bd = active ? (danger ? 0x8CE55353 : STROKE2) : Color.TRANSPARENT;
        TextView t = txt(ctx, text, 11, fg, active);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7));
        t.setBackground(box(ctx, fill, bd, 9));
        return t;
    }

    private static TextView filterChip(Context ctx, String text, boolean active) {
        TextView t = mono(ctx, text, 10.5f, active ? CYAN : DIM);
        if (active) t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(ctx, 11), dp(ctx, 5), dp(ctx, 11), dp(ctx, 5));
        t.setBackground(box(ctx, active ? 0x1F2EC4B6 : CARD2, active ? 0x6B2EC4B6 : STROKE, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(ctx, 6);
        t.setLayoutParams(lp);
        bindPress(t);
        return t;
    }

    private static TextView linkStrip(Context ctx, String text, boolean warn) {
        TextView t = mono(ctx, text, 10, warn ? AMBER : ICE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        t.setBackground(box(ctx, warn ? 0x1FE5A93B : 0x122EC4B6,
                warn ? 0x73E5A93B : 0x472EC4B6, 11));
        return t;
    }

    private static View miniSwitch(Context ctx, boolean on) {
        LinearLayout track = new LinearLayout(ctx);
        track.setGravity(on ? (Gravity.END | Gravity.CENTER_VERTICAL)
                : (Gravity.START | Gravity.CENTER_VERTICAL));
        track.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
        track.setBackground(box(ctx, on ? CYAN : STROKE2, Color.TRANSPARENT, 10));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 19));
        tlp.leftMargin = dp(ctx, 8);
        track.setLayoutParams(tlp);
        View thumb = new View(ctx);
        GradientDrawable td = new GradientDrawable();
        td.setShape(GradientDrawable.OVAL);
        td.setColor(Color.WHITE);
        thumb.setBackground(td);
        track.addView(thumb, new LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15)));
        return track;
    }

    private static EditText darkInput(Context ctx, String value, String hint) {
        EditText et = new EditText(ctx);
        et.setText(value == null ? "" : value);
        et.setHint(hint);
        et.setHintTextColor(MUTE);
        et.setTextColor(FG);
        et.setTextSize(12);
        et.setTypeface(Typeface.MONOSPACE);
        et.setSingleLine(true);
        et.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        et.setBackground(box(ctx, BG, STROKE, 10));
        return et;
    }

    private static TextView primaryBtn(Context ctx, String text, boolean danger) {
        TextView b = txt(ctx, text, 12, danger ? Color.WHITE : 0xFF041918, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        b.setBackground(box(ctx, danger ? RED : CYAN, danger ? RED : CYAN, 12));
        bindPress(b);
        return b;
    }

    private static TextView secondaryBtn(Context ctx, String text) {
        TextView b = txt(ctx, text, 11.5f, FG, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 13), dp(ctx, 10), dp(ctx, 13), dp(ctx, 10));
        b.setBackground(box(ctx, CARD2, STROKE, 12));
        bindPress(b);
        return b;
    }

    private static TextView tag(Context ctx, String text, int fg, int fill, int stroke) {
        TextView t = mono(ctx, text, 9.5f, fg);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(ctx, 7), dp(ctx, 2), dp(ctx, 7), dp(ctx, 2));
        t.setBackground(box(ctx, fill, stroke, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(ctx, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static TextView txt(Context ctx, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static TextView mono(Context ctx, String text, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private static GradientDrawable box(Context ctx, int fill, int stroke, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(ctx, radiusDp));
        if (stroke != Color.TRANSPARENT) {
            bg.setStroke(dp(ctx, 1), stroke);
        }
        return bg;
    }

    private static void bindPress(View view) {
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

    private static TextWatcher simpleWatcher(java.util.function.Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                onChange.accept(s == null ? "" : s.toString());
            }
        };
    }

    private static String brandDisplay(String manufacturer, String brand) {
        String raw = (manufacturer != null && !manufacturer.isEmpty()) ? manufacturer : brand;
        if (raw == null || raw.isEmpty()) return "Other";
        String low = raw.toLowerCase(Locale.ROOT);
        if (low.contains("google")) return "Google";
        if (low.contains("samsung")) return "Samsung";
        if (low.contains("xiaomi")) return "Xiaomi";
        if (low.contains("oneplus")) return "OnePlus";
        if (low.contains("sony")) return "Sony";
        if (low.contains("nothing")) return "Nothing";
        if (low.contains("asus")) return "ASUS";
        if (low.contains("oppo")) return "OPPO";
        if (low.contains("vivo")) return "vivo";
        if (low.contains("realme")) return "realme";
        if (low.contains("motorola")) return "Motorola";
        if (low.contains("fairphone")) return "Fairphone";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String shortFp(String fp) {
        if (fp == null || fp.isEmpty()) return "—";
        String[] parts = fp.split("[/:]");
        return parts.length >= 3 ? parts[0] + "/" + parts[1] + "/" + parts[2] : fp;
    }

    private static int dp(Context ctx, float v) {
        return UiSection.dp(ctx, v);
    }
}
