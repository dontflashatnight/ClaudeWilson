package com.walter.claudewilson;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.walter.claudewilson.model.GeoTable;

import java.util.List;
import java.util.Locale;

/**
 * Offline map selection, modelled on VirtualRegion's {@code MapPickerFragment}: a world map,
 * tap/long-press to drop a pin, a search box and a WGS-84 readout. VirtualRegion uses AMap tiles,
 * POI search and reverse geocoding; this app has no INTERNET permission and no map SDK, so the
 * map is a shipped Natural Earth render and search runs over the same {@code geo.txt} table that
 * supplies region centres.
 */
final class LocationPickerDialog {
    private LocationPickerDialog() {
    }

    static void show(Context context, Regions.R region, GeoTable.Point regionPoint,
            PersonaState persona, String user, Runnable onChanged) {
        if (context == null || regionPoint == null) {
            return;
        }
        final Dialog dialog = new Dialog(context,
                android.R.style.Theme_Material_NoActionBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final GeoTable table = Geo.table(context);
        final boolean hadCustom = persona.hasLocationPoint(user);
        final double[] point = new double[]{
                hadCustom ? persona.locationLatitude(user) : regionPoint.latitude,
                hadCustom ? persona.locationLongitude(user) : regionPoint.longitude
        };
        final boolean[] custom = new boolean[]{hadCustom};
        final String[] label = new String[]{hadCustom
                ? persona.locationCity(user) : regionLabel(region, regionPoint)};

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiSection.BG);
        root.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = text(context, "✕", 21, UiSection.FG, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(context, 6), 0, dp(context, 14), 0);
        close.setOnClickListener(v -> dialog.dismiss());
        top.addView(close);
        TextView title = text(context, Lang.t("定位", "location", "位置情報"), 17,
                UiSection.FG, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView save = text(context, Lang.t("保存", "save", "保存"), 14, UiSection.CYAN, true);
        save.setPadding(dp(context, 12), dp(context, 8), dp(context, 4), dp(context, 8));
        save.setOnClickListener(v -> {
            if (custom[0]) {
                persona.putLocationPoint(user, point[0], point[1], label[0]);
            } else {
                persona.clearLocationPoint(user);
            }
            persona.putLocation(user, true);
            onChanged.run();
            dialog.dismiss();
        });
        top.addView(save);
        root.addView(top);

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(UiSection.FG);
        search.setHintTextColor(UiSection.MUTE);
        search.setHint(Lang.t("搜索城市 / iso", "search city / iso", "都市 / iso を検索"));
        search.setBackground(UiSection.card(context));
        search.setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9));
        root.addView(search, params(context, 0));

        LinearLayout results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        root.addView(results);

        final LocationMapView map = new LocationMapView(context, table);
        root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView coords = text(context, "", 12, UiSection.FG, false);
        coords.setTypeface(Typeface.MONOSPACE);
        coords.setPadding(0, dp(context, 10), 0, 0);
        root.addView(coords);
        TextView place = text(context, "", 11, UiSection.DIM, false);
        place.setPadding(0, dp(context, 3), 0, dp(context, 8));
        root.addView(place);

        final TextView[] readout = new TextView[]{coords, place};
        final LocationMapView[] mapRef = new LocationMapView[]{map};

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView reset = chip(context, Lang.t("地区中心", "region centre", "地域中心"));
        reset.setOnClickListener(v -> {
            point[0] = regionPoint.latitude;
            point[1] = regionPoint.longitude;
            custom[0] = false;
            label[0] = regionLabel(region, regionPoint);
            mapRef[0].resetTo(point[0], point[1]);
            update(readout, point, label);
        });
        actions.addView(reset, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView off = chip(context, Lang.t("关闭模拟", "turn simulation off", "シミュレーションをオフ"));
        off.setOnClickListener(v -> {
            persona.putLocation(user, false);
            onChanged.run();
            dialog.dismiss();
        });
        actions.addView(off, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions, params(context, 4));

        map.setListener((latitude, longitude) -> {
            point[0] = latitude;
            point[1] = longitude;
            custom[0] = true;
            label[0] = nearestLabel(context, table, latitude, longitude);
            update(readout, point, label);
        });
        map.post(() -> map.resetTo(point[0], point[1]));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable s) {
                String query = s == null ? "" : s.toString().trim();
                results.removeAllViews();
                if (query.isEmpty()) {
                    results.setVisibility(View.GONE);
                    return;
                }
                List<GeoTable.Point> hits = table.search(query, 6);
                if (hits.isEmpty()) {
                    results.setVisibility(View.GONE);
                    return;
                }
                results.setVisibility(View.VISIBLE);
                for (GeoTable.Point hit : hits) {
                    TextView row = text(context, hitLabel(hit), 12, UiSection.FG, false);
                    row.setPadding(dp(context, 12), dp(context, 8), dp(context, 12),
                            dp(context, 8));
                    row.setBackground(UiSection.card(context));
                    row.setOnClickListener(v -> {
                        point[0] = hit.latitude;
                        point[1] = hit.longitude;
                        custom[0] = true;
                        label[0] = placeLabel(hit);
                        search.setText("");
                        search.clearFocus();
                        hideKeyboard(context, search);
                        mapRef[0].resetTo(point[0], point[1]);
                        update(readout, point, label);
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = dp(context, 4);
                    results.addView(row, lp);
                }
            }
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> hideKeyboard(context, search));
        update(readout, point, label);
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private static void update(TextView[] readout, double[] point, String[] label) {
        readout[0].setText(String.format(Locale.US, "WGS-84 %.6f, %.6f",
                point[0], point[1]));
        readout[1].setText(label[0]);
    }

    private static String regionLabel(Regions.R region, GeoTable.Point point) {
        if (point != null && !point.city.isEmpty()) {
            return point.city;
        }
        return region == null ? "" : region.name;
    }

    private static String nearestLabel(Context context, GeoTable table, double latitude,
            double longitude) {
        GeoTable.Point near = table.nearest(latitude, longitude);
        if (near != null && !near.city.isEmpty()
                && GeoTable.distanceKm(latitude, longitude, near.latitude, near.longitude)
                        < 60.0d) {
            return near.city;
        }
        return Lang.t("自定义位置", "custom location", "カスタム位置");
    }

    private static String hitLabel(GeoTable.Point point) {
        if (!point.city.isEmpty()) {
            return point.city + (point.iso.isEmpty() ? "" : " · " + point.iso.toUpperCase(Locale.ROOT));
        }
        return point.iso.toUpperCase(Locale.ROOT) + (point.zone.isEmpty() ? "" : " · " + point.zone);
    }

    /** What gets stored, without the search row's ISO suffix. */
    private static String placeLabel(GeoTable.Point point) {
        if (!point.city.isEmpty()) {
            return point.city;
        }
        return point.iso.toUpperCase(Locale.ROOT);
    }

    private static TextView text(Context context, String value, float size, int color,
            boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private static TextView chip(Context context, String label) {
        TextView view = text(context, label, 12, UiSection.FG, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
        view.setBackground(UiSection.card(context));
        view.setClickable(true);
        return view;
    }

    private static LinearLayout.LayoutParams params(Context context, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(context, top);
        return lp;
    }

    private static int dp(Context context, float value) {
        return UiSection.dp(context, value);
    }

    private static void hideKeyboard(Context context, View view) {
        InputMethodManager manager = (InputMethodManager) context.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
