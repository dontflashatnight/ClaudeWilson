package com.walter.claudewilson;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.CoordinateParser;
import com.walter.claudewilson.model.GeoTable;
import com.walter.claudewilson.model.PersonaEditRules;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Full-screen immersive offline GPS picker in warm obsidian-terracotta design language.
 *
 * <p>Zero INTERNET permission and zero map SDK: combines the DPI-native vector-enhanced offline
 * atlas ({@link LocationMapView}), expanded offline city catalog, one-tap clipboard coordinate /
 * Google Maps link parsing ({@link CoordinateParser}), accuracy/drift mode control, altitude
 * override, and cross-timezone guard.
 */
final class LocationPickerDialog {
    private static final int GLASS_BG = 0xEA0E1719;
    private static final int STROKE2 = 0xFF2B454B;

    private LocationPickerDialog() {
    }

    static void show(Context context, Regions.R region, GeoTable.Point regionPoint,
                     PersonaState persona, String user, Runnable onChanged) {
        show(context, region, regionPoint, persona, user, null, onChanged, null);
    }

    static void show(Context context, Regions.R region, GeoTable.Point regionPoint,
                     PersonaState persona, String user, String currentZone, Runnable onChanged,
                     Consumer<String> onSyncZone) {
        if (context == null || regionPoint == null) {
            return;
        }
        final Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final GeoTable table = Geo.table(context);

        final RegionPersona.Snapshot savedRegion =
                region != null ? RegionPersona.of(context, user, region) : null;
        final boolean hadCustom = persona.hasLocationPoint(user);
        final boolean hasSavedRegionPin = !hadCustom && savedRegion != null
                && ((savedRegion.city != null && !savedRegion.city.isEmpty())
                        || savedRegion.latitude != 0d || savedRegion.longitude != 0d);
        final double[] point = new double[]{
                hadCustom ? persona.locationLatitude(user)
                        : (hasSavedRegionPin ? savedRegion.latitude : regionPoint.latitude),
                hadCustom ? persona.locationLongitude(user)
                        : (hasSavedRegionPin ? savedRegion.longitude : regionPoint.longitude)
        };
        final double[] altitude = new double[]{persona.locationAltitude(user)};
        final boolean[] custom = new boolean[]{hadCustom || hasSavedRegionPin};
        final boolean[] locEnabled = new boolean[]{persona.location(user)};
        final String[] accMode = new String[]{persona.locationAccuracyMode(user)};
        final String[] label = new String[]{hadCustom
                ? persona.locationCity(user)
                : (hasSavedRegionPin && savedRegion.city != null && !savedRegion.city.isEmpty()
                        ? savedRegion.city : regionLabel(region, regionPoint))};
        final String[] activeZone = new String[]{currentZone == null ? "" : currentZone};
        final String[] pinZone = new String[]{
                resolvePinZone(table, point[0], point[1], regionPoint.zone)};
        final boolean[] fineTuneOpen = new boolean[]{false};

        // =====================================================================
        // ROOT IMMERSIVE FRAME (MAP FULL-BLEED + FLOATING TOP/RIGHT/BOTTOM UI)
        // =====================================================================
        final FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0xFF081012);

        final LocationMapView map = new LocationMapView(context, table);
        if (region != null) {
            map.setActiveIso(region.iso);
        }
        map.setAccuracyMode(accMode[0]);
        map.setSimEnabled(locEnabled[0]);
        frame.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // ---- FLOATING TOP BAR (Search + One-tap Clipboard Paste) ----
        final LinearLayout topWrap = new LinearLayout(context);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams topWrapLp = new FrameLayout.LayoutParams(-1, -2);
        topWrapLp.gravity = Gravity.TOP;
        frame.addView(topWrap, topWrapLp);

        LinearLayout searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(context, 6), dp(context, 5), dp(context, 7), dp(context, 5));
        searchBar.setBackground(roundBox(context, GLASS_BG, STROKE2, 16));

        TextView closeBtn = text(context, "✕", 16, UiSection.FG, false);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        bindPress(closeBtn);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        searchBar.addView(closeBtn);

        final EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(12.5f);
        search.setTextColor(UiSection.FG);
        search.setHintTextColor(UiSection.MUTE);
        search.setHint(Lang.t("搜城市 / 粘贴坐标或谷歌地图链接",
                "City / paste lat,lng or Maps URL", "都市 / 座標・マップURL貼付"));
        search.setBackground(null);
        search.setPadding(dp(context, 2), dp(context, 6), dp(context, 6), dp(context, 6));
        search.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        searchBar.addView(search);

        TextView pasteBtn = text(context, Lang.t("粘贴", "Paste", "貼付"), 10.5f, UiSection.ACCENT, true);
        pasteBtn.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        pasteBtn.setBackground(roundBox(context, 0x222EC4B6, 0x732EC4B6, 11));
        bindPress(pasteBtn);
        searchBar.addView(pasteBtn);
        topWrap.addView(searchBar);

        final LinearLayout results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 2));
        results.setBackground(roundBox(context, 0xF50E1719, STROKE2, 14));
        results.setVisibility(View.GONE);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(-1, -2);
        resLp.topMargin = dp(context, 6);
        topWrap.addView(results, resLp);

        // ---- FLOATING RIGHT MAP CONTROLS (+ / - / ◎) ----
        final LinearLayout zoomStack = new LinearLayout(context);
        zoomStack.setOrientation(LinearLayout.VERTICAL);
        zoomStack.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        zoomStack.setBackground(roundBox(context, GLASS_BG, STROKE2, 14));
        zoomStack.addView(mapToolBtn(context, "+", () -> map.zoomBy(1.7f)));
        zoomStack.addView(thinSep(context));
        zoomStack.addView(mapToolBtn(context, "−", () -> map.zoomBy(0.58f)));
        zoomStack.addView(thinSep(context));
        zoomStack.addView(mapToolBtn(context, "◎", map::centerOnPin));
        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(-2, -2);
        zoomLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        zoomLp.rightMargin = dp(context, 14);
        frame.addView(zoomStack, zoomLp);

        // ---- FLOATING BOTTOM CONTROL CARD ----
        final LinearLayout bottomCard = new LinearLayout(context);
        bottomCard.setOrientation(LinearLayout.VERTICAL);
        bottomCard.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        bottomCard.setBackground(roundBox(context, GLASS_BG, STROKE2, 20));
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(-1, -2);
        botLp.gravity = Gravity.BOTTOM;
        frame.addView(bottomCard, botLp);

        // Row 1: Place + Coordinates + Fine-tune toggle
        LinearLayout headRow = new LinearLayout(context);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout placeCol = new LinearLayout(context);
        placeCol.setOrientation(LinearLayout.VERTICAL);
        placeCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView placeTv = text(context, "", 14.5f, UiSection.FG, true);
        placeTv.setSingleLine(true);
        placeTv.setEllipsize(TextUtils.TruncateAt.END);
        placeCol.addView(placeTv);
        final TextView coordsTv = text(context, "", 10.5f, UiSection.SAND, false);
        coordsTv.setTypeface(Typeface.MONOSPACE);
        coordsTv.setSingleLine(true);
        coordsTv.setPadding(0, dp(context, 2), 0, 0);
        placeCol.addView(coordsTv);
        headRow.addView(placeCol);

        final TextView fineBtn = text(context, Lang.t("坐标精调", "Coords", "座標編集"),
                10, UiSection.DIM, true);
        fineBtn.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        fineBtn.setBackground(roundBox(context, UiSection.CARD2, UiSection.STROKE, 11));
        bindPress(fineBtn);
        headRow.addView(fineBtn);
        bottomCard.addView(headRow);

        // Collapsible Inline Lat/Lng/Altitude Editor
        final LinearLayout fineBox = new LinearLayout(context);
        fineBox.setOrientation(LinearLayout.HORIZONTAL);
        fineBox.setGravity(Gravity.CENTER_VERTICAL);
        fineBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(-1, -2);
        fblp.topMargin = dp(context, 8);
        fineBox.setLayoutParams(fblp);

        final EditText latEt = miniNumInput(context, Lang.t("纬度", "Lat"));
        final EditText lngEt = miniNumInput(context, Lang.t("经度", "Lng"));
        final EditText altEt = miniNumInput(context, Lang.t("海拔m", "Alt m"));
        LinearLayout.LayoutParams n1 = new LinearLayout.LayoutParams(0, -2, 1f);
        n1.rightMargin = dp(context, 5);
        fineBox.addView(latEt, n1);
        LinearLayout.LayoutParams n2 = new LinearLayout.LayoutParams(0, -2, 1f);
        n2.rightMargin = dp(context, 5);
        fineBox.addView(lngEt, n2);
        LinearLayout.LayoutParams n3 = new LinearLayout.LayoutParams(0, -2, 0.72f);
        n3.rightMargin = dp(context, 6);
        fineBox.addView(altEt, n3);

        TextView applyCoord = text(context, Lang.t("落针", "Set", "適用"), 10, 0xFF041918, true);
        applyCoord.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
        applyCoord.setBackground(roundBox(context, UiSection.ACCENT, UiSection.ACCENT, 10));
        bindPress(applyCoord);
        fineBox.addView(applyCoord);
        bottomCard.addView(fineBox);

        // Cross-Timezone Consistency Guard
        final LinearLayout tzGuard = new LinearLayout(context);
        tzGuard.setOrientation(LinearLayout.HORIZONTAL);
        tzGuard.setGravity(Gravity.CENTER_VERTICAL);
        tzGuard.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
        tzGuard.setBackground(roundBox(context, 0x1FE5A93B, 0x73E5A93B, 11));
        tzGuard.setVisibility(View.GONE);
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(-1, -2);
        tglp.topMargin = dp(context, 8);
        tzGuard.setLayoutParams(tglp);

        final TextView tzGuardMsg = text(context, "", 10, UiSection.AMBER, false);
        tzGuardMsg.setTypeface(Typeface.MONOSPACE);
        tzGuardMsg.setSingleLine(true);
        tzGuardMsg.setEllipsize(TextUtils.TruncateAt.END);
        tzGuardMsg.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tzGuard.addView(tzGuardMsg);

        final TextView tzSyncPill = text(context, Lang.t("同步时区", "Sync TZ", "TZ同期"),
                10, UiSection.AMBER, true);
        tzSyncPill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tzSyncPill.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        tzSyncPill.setBackground(roundBox(context, 0x2AE5A93B, UiSection.AMBER, 10));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
        splp.leftMargin = dp(context, 8);
        tzSyncPill.setLayoutParams(splp);
        bindPress(tzSyncPill);
        tzGuard.addView(tzSyncPill);
        bottomCard.addView(tzGuard);

        // Region Quick City Chips
        final List<GeoTable.Point> regionCities = region != null
                ? table.citiesForIso(region.iso) : Collections.<GeoTable.Point>emptyList();
        final LinearLayout cityBar = new LinearLayout(context);
        cityBar.setOrientation(LinearLayout.HORIZONTAL);
        if (!regionCities.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(context);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.addView(cityBar);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = dp(context, 9);
            bottomCard.addView(hsv, hlp);
        }

        // Accuracy & Simulation Segmented Switch (±5m / ±15m / ±3km / Off)
        final LinearLayout accRow = new LinearLayout(context);
        accRow.setOrientation(LinearLayout.HORIZONTAL);
        accRow.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        accRow.setBackground(roundBox(context, UiSection.BG, UiSection.STROKE, 11));
        LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(-1, -2);
        aclp.topMargin = dp(context, 9);
        bottomCard.addView(accRow, aclp);

        // Footer Actions: Region Centre + Save
        LinearLayout foot = new LinearLayout(context);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(context, 10);
        foot.setLayoutParams(flp);

        TextView resetBtn = text(context, Lang.t("地区中心", "Centre", "中心"), 11.5f, UiSection.DIM, true);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        resetBtn.setBackground(roundBox(context, UiSection.CARD2, UiSection.STROKE, 12));
        bindPress(resetBtn);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.rightMargin = dp(context, 8);
        foot.addView(resetBtn, rlp);

        TextView saveBtn = text(context, Lang.t("保存定位", "Save Location", "位置を保存"),
                12, 0xFF041918, true);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        saveBtn.setBackground(roundBox(context, UiSection.ACCENT, UiSection.ACCENT, 12));
        bindPress(saveBtn);
        foot.addView(saveBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        bottomCard.addView(foot);

        // ---- STATE REFRESH CLOSURES ----
        final Runnable[] refreshAll = new Runnable[1];
        refreshAll[0] = () -> {
            placeTv.setText(locEnabled[0]
                    ? (label[0] == null || label[0].isEmpty()
                            ? Lang.t("自定义坐标", "Custom pin", "カスタム座標") : label[0])
                    : Lang.t("定位模拟已关闭", "Location simulation off", "位置偽装オフ"));
            placeTv.setTextColor(locEnabled[0] ? UiSection.FG : UiSection.MUTE);

            String altTag = Double.isNaN(altitude[0])
                    ? ""
                    : " · " + Math.round(altitude[0]) + "m";
            coordsTv.setText(String.format(Locale.US, "%.5f, %.5f%s",
                    point[0], point[1], altTag));
            coordsTv.setTextColor(locEnabled[0] ? UiSection.SAND : UiSection.MUTE);

            map.setAccuracyMode(accMode[0]);
            map.setSimEnabled(locEnabled[0]);

            // Sync fine-tune inputs if closed
            if (!fineTuneOpen[0]) {
                latEt.setText(String.format(Locale.US, "%.5f", point[0]));
                lngEt.setText(String.format(Locale.US, "%.5f", point[1]));
                altEt.setText(Double.isNaN(altitude[0]) ? "" : String.valueOf(Math.round(altitude[0])));
            }
            fineBox.setVisibility(fineTuneOpen[0] ? View.VISIBLE : View.GONE);
            fineBtn.setTextColor(fineTuneOpen[0] ? UiSection.ACCENT : UiSection.DIM);
            fineBtn.setBackground(roundBox(context,
                    fineTuneOpen[0] ? 0x1F2EC4B6 : UiSection.CARD2,
                    fineTuneOpen[0] ? UiSection.ACCENT : UiSection.STROKE, 11));

            // Cross-TZ guard
            boolean mismatch = locEnabled[0] && onSyncZone != null
                    && !pinZone[0].isEmpty() && !activeZone[0].isEmpty()
                    && PersonaEditRules.tzOffsetDiffers(
                            pinZone[0], activeZone[0], System.currentTimeMillis());
            if (mismatch) {
                tzGuardMsg.setText("⚠ " + label[0] + " → " + pinZone[0]);
                tzGuard.setVisibility(View.VISIBLE);
            } else {
                tzGuard.setVisibility(View.GONE);
            }

            // Rebuild city chips
            cityBar.removeAllViews();
            for (final GeoTable.Point city : regionCities) {
                boolean sel = locEnabled[0] && city.city.equalsIgnoreCase(label[0]);
                TextView ch = text(context, city.city, 10.5f,
                        sel ? UiSection.ACCENT : UiSection.DIM, sel);
                ch.setTypeface(Typeface.MONOSPACE, sel ? Typeface.BOLD : Typeface.NORMAL);
                ch.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
                ch.setBackground(roundBox(context,
                        sel ? 0x222EC4B6 : UiSection.CARD2,
                        sel ? 0x732EC4B6 : UiSection.STROKE, 12));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.rightMargin = dp(context, 6);
                ch.setLayoutParams(clp);
                bindPress(ch);
                ch.setOnClickListener(v -> {
                    locEnabled[0] = true;
                    point[0] = city.latitude;
                    point[1] = city.longitude;
                    custom[0] = true;
                    label[0] = city.city;
                    pinZone[0] = resolvePinZone(table, city.latitude, city.longitude, city.zone);
                    map.resetTo(point[0], point[1]);
                    refreshAll[0].run();
                });
                cityBar.addView(ch);
            }

            // Rebuild accuracy segmented toggle
            accRow.removeAllViews();
            String[][] modes = {
                    {"fine", Lang.t("精 ±5m", "Fine ±5m", "精 ±5m")},
                    {"drift", Lang.t("漂 ±15m", "Drift", "微漂")},
                    {"coarse", Lang.t("粗 ±3km", "Coarse", "粗 ±3km")},
                    {"off", Lang.t("关闭", "Off", "オフ")},
            };
            for (final String[] m : modes) {
                boolean sel = ("off".equals(m[0]) && !locEnabled[0])
                        || (!"off".equals(m[0]) && locEnabled[0] && m[0].equals(accMode[0]));
                TextView b = text(context, m[1], 10,
                        sel ? ("off".equals(m[0]) ? UiSection.RED : 0xFF041918) : UiSection.DIM,
                        sel);
                b.setGravity(Gravity.CENTER);
                b.setSingleLine(true);
                b.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6));
                if (sel) {
                    int fill = "off".equals(m[0]) ? 0x24E55353 : UiSection.ACCENT;
                    int st = "off".equals(m[0]) ? 0x8CE55353 : UiSection.ACCENT;
                    b.setBackground(roundBox(context, fill, st, 8));
                }
                b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                bindPress(b);
                b.setOnClickListener(v -> {
                    if ("off".equals(m[0])) {
                        locEnabled[0] = false;
                    } else {
                        locEnabled[0] = true;
                        accMode[0] = m[0];
                    }
                    refreshAll[0].run();
                });
                accRow.addView(b);
            }
        };

        // ---- INTERACTIONS ----
        fineBtn.setOnClickListener(v -> {
            fineTuneOpen[0] = !fineTuneOpen[0];
            refreshAll[0].run();
        });

        applyCoord.setOnClickListener(v -> {
            try {
                double la = Double.parseDouble(latEt.getText().toString().trim());
                double ln = Double.parseDouble(lngEt.getText().toString().trim());
                if (la < -90d || la > 90d || ln < -180d || ln > 180d) {
                    Toast.makeText(context, Lang.t("经纬度超界", "Out of range"), Toast.LENGTH_SHORT).show();
                    return;
                }
                String aStr = altEt.getText().toString().trim();
                altitude[0] = aStr.isEmpty() ? Double.NaN : Double.parseDouble(aStr);
                locEnabled[0] = true;
                point[0] = la;
                point[1] = ln;
                custom[0] = true;
                label[0] = nearestLabel(context, table, la, ln);
                pinZone[0] = resolvePinZone(table, la, ln, "");
                fineTuneOpen[0] = false;
                hideKeyboard(context, latEt);
                map.resetTo(la, ln);
                refreshAll[0].run();
            } catch (NumberFormatException e) {
                Toast.makeText(context, Lang.t("请输入有效数字", "Invalid number"), Toast.LENGTH_SHORT).show();
            }
        });

        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence clip = null;
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData data = cm.getPrimaryClip();
                if (data != null && data.getItemCount() > 0) {
                    clip = data.getItemAt(0).coerceToText(context);
                }
            }
            CoordinateParser.LatLng parsed = CoordinateParser.parse(clip == null ? "" : clip.toString());
            if (parsed == null) {
                Toast.makeText(context,
                        Lang.t("剪贴板未识别到坐标或地图链接", "No coordinates in clipboard"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            locEnabled[0] = true;
            point[0] = parsed.latitude;
            point[1] = parsed.longitude;
            custom[0] = true;
            label[0] = nearestLabel(context, table, parsed.latitude, parsed.longitude);
            pinZone[0] = resolvePinZone(table, parsed.latitude, parsed.longitude, "");
            map.resetTo(point[0], point[1]);
            refreshAll[0].run();
            Toast.makeText(context,
                    String.format(Locale.US, "%.5f, %.5f", point[0], point[1]),
                    Toast.LENGTH_SHORT).show();
        });

        tzSyncPill.setOnClickListener(v -> {
            if (onSyncZone != null && !pinZone[0].isEmpty()) {
                activeZone[0] = pinZone[0];
                onSyncZone.accept(pinZone[0]);
                refreshAll[0].run();
            }
        });

        resetBtn.setOnClickListener(v -> {
            locEnabled[0] = true;
            point[0] = regionPoint.latitude;
            point[1] = regionPoint.longitude;
            altitude[0] = Double.NaN;
            custom[0] = false;
            label[0] = regionLabel(region, regionPoint);
            pinZone[0] = resolvePinZone(table, point[0], point[1], regionPoint.zone);
            map.resetTo(point[0], point[1]);
            refreshAll[0].run();
        });

        saveBtn.setOnClickListener(v -> {
            if (custom[0]) {
                persona.putLocationPoint(user, point[0], point[1], label[0]);
                if (region != null) {
                    RegionPersona.putLocation(context, user, region, point[0], point[1], label[0]);
                }
            } else {
                persona.clearLocationPoint(user);
                if (region != null) {
                    RegionPersona.putLocation(context, user, region,
                            regionPoint.latitude, regionPoint.longitude, regionPoint.city);
                }
            }
            persona.putLocation(user, locEnabled[0]);
            persona.putLocationAccuracyMode(user, accMode[0]);
            persona.putLocationAltitude(user, altitude[0]);
            onChanged.run();
            dialog.dismiss();
        });

        map.setListener((latitude, longitude) -> {
            locEnabled[0] = true;
            point[0] = latitude;
            point[1] = longitude;
            custom[0] = true;
            label[0] = nearestLabel(context, table, latitude, longitude);
            pinZone[0] = resolvePinZone(table, latitude, longitude, "");
            refreshAll[0].run();
        });

        // Search input supports both offline city search AND pasted coordinates / Google Maps URLs
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String query = s == null ? "" : s.toString().trim();
                results.removeAllViews();
                if (query.isEmpty()) {
                    results.setVisibility(View.GONE);
                    return;
                }
                CoordinateParser.LatLng direct = CoordinateParser.parse(query);
                if (direct != null) {
                    TextView row = resultRow(context,
                            Lang.t("落针 · ", "Pin · ") + String.format(Locale.US, "%.5f, %.5f",
                                    direct.latitude, direct.longitude),
                            UiSection.ACCENT);
                    row.setOnClickListener(v -> {
                        locEnabled[0] = true;
                        point[0] = direct.latitude;
                        point[1] = direct.longitude;
                        custom[0] = true;
                        label[0] = nearestLabel(context, table, direct.latitude, direct.longitude);
                        pinZone[0] = resolvePinZone(table, direct.latitude, direct.longitude, "");
                        results.setVisibility(View.GONE);
                        search.setText("");
                        hideKeyboard(context, search);
                        map.resetTo(point[0], point[1]);
                        refreshAll[0].run();
                    });
                    results.addView(row);
                    results.setVisibility(View.VISIBLE);
                    return;
                }
                List<GeoTable.Point> hits = table.search(query, 6);
                if (hits.isEmpty()) {
                    results.setVisibility(View.GONE);
                    return;
                }
                results.setVisibility(View.VISIBLE);
                for (final GeoTable.Point hit : hits) {
                    TextView row = resultRow(context, hitLabel(hit), UiSection.FG);
                    row.setOnClickListener(v -> {
                        locEnabled[0] = true;
                        point[0] = hit.latitude;
                        point[1] = hit.longitude;
                        custom[0] = true;
                        label[0] = placeLabel(hit);
                        pinZone[0] = resolvePinZone(table, hit.latitude, hit.longitude, hit.zone);
                        results.setVisibility(View.GONE);
                        search.setText("");
                        hideKeyboard(context, search);
                        map.resetTo(point[0], point[1]);
                        refreshAll[0].run();
                    });
                    results.addView(row);
                }
            }
        });

        // Edge-to-edge inset padding on floating top & bottom cards
        final int padH = dp(context, 14);
        frame.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
            int botInset = Math.max(bars.bottom, ime.bottom);

            FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) topWrap.getLayoutParams();
            tlp.leftMargin = bars.left + padH;
            tlp.rightMargin = bars.right + padH;
            tlp.topMargin = bars.top + dp(context, 10);
            topWrap.setLayoutParams(tlp);

            FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bottomCard.getLayoutParams();
            blp.leftMargin = bars.left + padH;
            blp.rightMargin = bars.right + padH;
            blp.bottomMargin = botInset + dp(context, 12);
            bottomCard.setLayoutParams(blp);
            return WindowInsets.CONSUMED;
        });

        refreshAll[0].run();
        bottomCard.post(() -> {
            map.setBottomCardOffsetPx(bottomCard.getHeight() + dp(context, 28));
            map.resetTo(point[0], point[1]);
        });

        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(0xFF081012));
            configureEdgeToEdge(window);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        frame.requestApplyInsets();
    }

    private static TextView mapToolBtn(Context ctx, String symbol, Runnable onClick) {
        TextView tv = text(ctx, symbol, 15, UiSection.FG, true);
        tv.setGravity(Gravity.CENTER);
        int sz = dp(ctx, 36);
        tv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        bindPress(tv);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private static View thinSep(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiSection.STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 24), 1));
        ((LinearLayout.LayoutParams) v.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        return v;
    }

    private static EditText miniNumInput(Context ctx, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(UiSection.MUTE);
        et.setTextColor(UiSection.FG);
        et.setTextSize(10.5f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        et.setBackground(roundBox(ctx, UiSection.BG, UiSection.STROKE, 9));
        return et;
    }

    private static TextView resultRow(Context ctx, String text, int color) {
        TextView row = text(ctx, text, 11.5f, color, false);
        row.setTypeface(Typeface.MONOSPACE);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9));
        row.setBackground(roundBox(ctx, UiSection.CARD2, UiSection.STROKE, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(ctx, 4);
        row.setLayoutParams(lp);
        bindPress(row);
        return row;
    }

    private static GradientDrawable roundBox(Context ctx, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setStroke(dp(ctx, 1), stroke);
        return g;
    }

    private static void bindPress(View view) {
        view.setClickable(true);
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(75).start();
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

    @SuppressWarnings("deprecation")
    private static void configureEdgeToEdge(Window window) {
        window.setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(lp);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(0, lightBars);
        }
    }

    private static String regionLabel(Regions.R region, GeoTable.Point point) {
        if (!point.city.isEmpty()) {
            return point.city;
        }
        return region != null ? region.dispName() : point.iso.toUpperCase(Locale.ROOT);
    }

    private static String placeLabel(GeoTable.Point point) {
        if (point == null) {
            return "";
        }
        if (!point.city.isEmpty()) {
            return point.city;
        }
        return point.iso.toUpperCase(Locale.ROOT);
    }

    private static String hitLabel(GeoTable.Point point) {
        String iso = point.iso.toUpperCase(Locale.ROOT);
        if (point.city.isEmpty()) {
            return iso;
        }
        return point.city + " · " + iso;
    }

    private static String resolvePinZone(GeoTable table, double lat, double lng, String directZone) {
        if (directZone != null && !directZone.isEmpty()) {
            return directZone;
        }
        GeoTable.Point nearest = table.nearest(lat, lng);
        if (nearest != null && nearest.zone != null && !nearest.zone.isEmpty()) {
            return nearest.zone;
        }
        if (nearest != null && !nearest.iso.isEmpty()) {
            for (Regions.R rr : Regions.ALL) {
                if (rr.iso.equalsIgnoreCase(nearest.iso)
                        && rr.zones != null && rr.zones.length > 0 && !rr.zones[0].isEmpty()) {
                    return rr.zones[0];
                }
            }
        }
        return "";
    }

    private static String nearestLabel(Context context, GeoTable table, double latitude, double longitude) {
        GeoTable.Point nearest = table.nearest(latitude, longitude);
        if (nearest == null) {
            return Lang.t("自定义坐标", "Custom pin", "カスタム座標");
        }
        String place = placeLabel(nearest);
        if (place.isEmpty()) {
            return Lang.t("自定义坐标", "Custom pin", "カスタム座標");
        }
        return Lang.t("近 ", "Near ", "付近 ") + place;
    }

    private static void hideKeyboard(Context context, View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static int dp(Context context, float value) {
        return UiSection.dp(context, value);
    }
}
