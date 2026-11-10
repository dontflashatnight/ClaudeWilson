package com.walter.claudewilson;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.CheckConstraint;
import com.walter.claudewilson.model.CheckItem;
import com.walter.claudewilson.model.RiskModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dense renderer for the check-item model.
 *
 * <p>One row is a check item, not a probe: the glyph is the verdict, the monospace text is the
 * value the target reads, and the second line names the exact store plus the write path. The
 * row itself is not a detail entry; editable rows carry a direct action badge.
 */
final class RiskModelPanel extends UiSection {
    private static final String PREFS = "risk_ui";
    private static final String PASS_OPEN = "pass_open";
    /** Region fields live in the persona space; the risk list must not duplicate them. */
    private static final Set<String> REGION_FIELDS = new LinkedHashSet<String>(Arrays.asList(
            "system.sim_operator", "system.sim_iso", "system.network_iso",
            "system.carrier", "system.timezone", "system.tz_offset",
            "system.sales_code", "system.locale", "system.product_locale",
            "system.build", "system.sim_carrier_id",
            "target.payload.cc_hint", "target.data.country_hint",
            "account.play_country"));

    static boolean isRegionField(String id) {
        return REGION_FIELDS.contains(id);
    }

    /** Keep the channel prefix while dropping the shared long store prefix. */
    private static String displayId(String id) {
        String prefix = "transport.datatransport.";
        if (id.startsWith(prefix)) {
            return "transport." + id.substring(prefix.length());
        }
        prefix = "sdk.datadog.";
        if (id.startsWith(prefix)) {
            return "datadog." + id.substring(prefix.length());
        }
        prefix = "permission.location.";
        if (id.startsWith(prefix)) {
            return "permission." + id.substring(prefix.length());
        }
        if ("permission.screen_capture".equals(id)) {
            return "permission.screen";
        }
        return id;
    }
    /** Pass rows are noise until the user asks for them; this survives a full render. */
    private static volatile boolean showPass;
    private final SharedPreferences prefs;

    /** The writes this panel is allowed to request; the Dashboard owns the actual actions. */
    interface Actions {
        void editTargetCountryHint(String iso);
        void editPayloadCountryHint(String iso);
        void clearPayloadCountryHint();
        void restoreTargetCountryHint();
        void resetLocalIdentity();
        void applyRegion();
    }

    private final Actions actions;

    RiskModelPanel(android.content.Context context, LinearLayout root, Actions actions) {
        super(context);
        this.root = root;
        this.actions = actions;
        this.prefs = context.getSharedPreferences(PREFS, 0);
        showPass = prefs.getBoolean(PASS_OPEN, false);
    }

    void render(RiskModel model) {
        section(Lang.t("目标视角", "Target view"));
        if (model == null) {
            card(Lang.t("扫描中…", "scanning…"), DIM);
            return;
        }
        for (String channel : model.channels()) {
            List<CheckItem> items = new ArrayList<CheckItem>();
            for (CheckItem item : model.items(channel)) {
                if (!REGION_FIELDS.contains(item.id())) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, dp(this, 12), 0, dp(this, 2));
            TextView label = txt(channelLabel(channel), 10, MUTE);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setLetterSpacing(0.03f);
            header.addView(label);
            root.addView(header);
            for (CheckItem item : items) {
                valueRow(item);
            }
        }
    }

    private static String channelLabel(String channel) {
        switch (channel) {
            case "identity":
                return Lang.t("身份", "identity");
            case "account":
                return Lang.t("账号", "account");
            case "integrity":
                return Lang.t("完整性", "integrity");
            case "transport":
                return Lang.t("传输", "transport");
            case "target":
                return Lang.t("目标进程", "target process");
            case "system":
                return Lang.t("系统", "system");
            case "permission":
                return Lang.t("权限", "permissions");
            case "location":
                return Lang.t("定位", "location");
            default:
                return channel;
        }
    }

    /** A target-view row: the observed value and its store, never a pass/fail comparison. */
    private void valueRow(CheckItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(this, 2), dp(this, 5), dp(this, 2), dp(this, 5));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView id = txt(displayId(item.id()), 10, FG);
        id.setTypeface(Typeface.MONOSPACE);
        id.setSingleLine(true);
        id.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        id.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.25f));
        top.addView(id);

        TextView value = txt(valueText(item), 9.5f, item.isRead() ? FG : MUTE);
        value.setTypeface(Typeface.MONOSPACE);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        value.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.75f));
        top.addView(value);

        String badge = writableBadge(item);
        if (!badge.isEmpty()) {
            TextView writable = txt(badge, 8.5f, writableColor(item));
            writable.setTypeface(Typeface.DEFAULT_BOLD);
            writable.setGravity(Gravity.CENTER);
            writable.setPadding(dp(this, 5), dp(this, 1), dp(this, 5), dp(this, 1));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD2);
            bg.setCornerRadius(dp(this, 7));
            bg.setStroke(dp(this, 1), writableColor(item));
            writable.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(this, 6);
            writable.setLayoutParams(lp);
            if (isEditable(item)) {
                writable.setOnClickListener(v -> edit(item));
            }
            top.addView(writable);
        }
        row.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(this, 16), dp(this, 1), 0, 0);
        TextView source = txt(valueSource(item), 8.5f, MUTE);
        source.setTypeface(Typeface.MONOSPACE);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(source);
        row.addView(bottom);

        root.addView(row);
    }

    private void group(String title, List<CheckItem> items, int color, boolean collapsible,
                       RiskModel model) {
        if (items.isEmpty()) {
            return;
        }
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(this, 14), 0, dp(this, 4));
        TextView label = txt((collapsible ? (showPass ? "▾ " : "▸ ") : "") + title
                + "  " + items.size(), 10, color);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.03f);
        header.addView(label);
        root.addView(header);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (CheckItem item : items) {
            row(item, model, body);
        }
        root.addView(body);
        if (collapsible) {
            body.setVisibility(showPass ? View.VISIBLE : View.GONE);
            header.setOnClickListener(v -> {
                showPass = !showPass;
                prefs.edit().putBoolean(PASS_OPEN, showPass).apply();
                body.setVisibility(showPass ? View.VISIBLE : View.GONE);
                label.setText((showPass ? "▾ " : "▸ ") + title + "  " + items.size());
            });
        }
    }

    /**
     * Blind spots are not actions. The header counts by reason, and only rows whose reason can
     * change (not covered, no record, unreadable, stale, unresolved relation) are listed. Observed
     * boundaries are one count: there is nothing to open.
     */
    private void noActionBlock(List<CheckItem> items, RiskModel model) {
        if (items.isEmpty()) {
            return;
        }
        int notCovered = 0;
        int noRecord = 0;
        int unreadable = 0;
        int serverOnly = 0;
        int stale = 0;
        int boundary = 0;
        int unresolved = 0;
        for (CheckItem item : items) {
            if (item.isStale()) {
                stale++;
            } else if (!item.isRead()) {
                switch (item.coverage()) {
                    case NOT_COVERED:
                        notCovered++;
                        break;
                    case NO_RECORD:
                        noRecord++;
                        break;
                    case SERVER_ONLY:
                        serverOnly++;
                        break;
                    case STALE:
                        stale++;
                        break;
                    default:
                        unreadable++;
                        break;
                }
            } else if (item.verdict() == CheckItem.Verdict.BOUNDARY) {
                boundary++;
            } else {
                switch (dependencyReason(item, model)) {
                    case DEP_STALE:
                        stale++;
                        break;
                    case DEP_BOUNDARY:
                        boundary++;
                        break;
                    default:
                        unresolved++;
                        break;
                }
            }
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(this, 14), 0, dp(this, 4));
        TextView label = txt(Lang.t("无需操作", "no action") + "  " + items.size(),
                10, MUTE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.03f);
        header.addView(label);
        root.addView(header);

        StringBuilder reasons = new StringBuilder();
        appendReason(reasons, Lang.t("未覆盖", "not covered"), notCovered);
        appendReason(reasons, Lang.t("无记录", "no record"), noRecord);
        appendReason(reasons, Lang.t("读不到", "unreadable"), unreadable);
        appendReason(reasons, Lang.t("仅服务端", "server only"), serverOnly);
        appendReason(reasons, Lang.t("已过期", "stale"), stale);
        appendReason(reasons, Lang.t("关联未定", "unresolved"), unresolved);
        appendReason(reasons, Lang.t("不可改", "cannot change"), boundary);
        if (reasons.length() > 0) {
            TextView counts = txt(reasons.toString(), 9, MUTE);
            counts.setTypeface(Typeface.MONOSPACE);
            counts.setPadding(dp(this, 16), 0, 0, dp(this, 4));
            counts.setSingleLine(false);
            root.addView(counts);
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (CheckItem item : items) {
            row(item, model, body);
        }
        root.addView(body);
    }

    private static final int DEP_NONE = 0;
    private static final int DEP_STALE = 1;
    private static final int DEP_BOUNDARY = 2;

    /**
     * A readable item can have an unresolved relation because its peer is stale or structurally
     * absent. That is not the same gap as an unreadable peer: show the peer's reason instead of
     * a generic "unresolved" label.
     */
    private static int dependencyReason(CheckItem item, RiskModel model) {
        if (model == null || item.verdict() != CheckItem.Verdict.UNKNOWN || !item.isRead()) {
            return DEP_NONE;
        }
        int result = DEP_NONE;
        for (CheckConstraint constraint : model.unknownsFor(item.id())) {
            String peerId = constraint.leftId().equals(item.id())
                    ? constraint.rightId() : constraint.leftId();
            CheckItem peer = model.item(peerId);
            if (peer == null) {
                continue;
            }
            if (peer.isStale()) {
                return DEP_STALE;
            }
            if (!peer.isRead()) {
                CheckItem.Coverage coverage = peer.coverage();
                if (coverage == CheckItem.Coverage.NO_RECORD
                        || coverage == CheckItem.Coverage.NOT_COVERED
                        || coverage == CheckItem.Coverage.SERVER_ONLY) {
                    result = DEP_BOUNDARY;
                }
            }
        }
        return result;
    }

    private static void appendReason(StringBuilder b, String label, int count) {
        if (count <= 0) {
            return;
        }
        if (b.length() > 0) {
            b.append(" · ");
        }
        b.append(label).append(' ').append(count);
    }

    private void row(CheckItem item, RiskModel model, LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(this, 2), dp(this, 5), dp(this, 2), dp(this, 5));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView glyph = txt(glyph(item.verdict()), 11, ColorOf(item.verdict()));
        glyph.setTypeface(Typeface.DEFAULT_BOLD);
        glyph.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 16), -2));
        top.addView(glyph);

        TextView id = txt(displayId(item.id()), 10, FG);
        id.setTypeface(Typeface.MONOSPACE);
        id.setSingleLine(true);
        // Keep the suffix visible: several transport ids share the same long prefix.
        id.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        id.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.2f));
        top.addView(id);

        TextView value = txt(valueText(item), 9.5f, valueColor(item));
        value.setTypeface(Typeface.MONOSPACE);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.END);
        value.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.8f));
        top.addView(value);

        String badge = writableBadge(item);
        if (!badge.isEmpty()) {
            TextView writable = txt(badge, 8.5f, writableColor(item));
            writable.setTypeface(Typeface.DEFAULT_BOLD);
            writable.setGravity(Gravity.CENTER);
            writable.setPadding(dp(this, 5), dp(this, 1), dp(this, 5), dp(this, 1));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD2);
            bg.setCornerRadius(dp(this, 7));
            bg.setStroke(dp(this, 1), writableColor(item));
            writable.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(this, 6);
            writable.setLayoutParams(lp);
            if (isEditable(item)) {
                writable.setOnClickListener(v -> edit(item));
            }
            top.addView(writable);
        }
        row.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(this, 16), dp(this, 1), 0, 0);
        TextView source = txt(sourceLine(item, model), 8.5f, MUTE);
        source.setTypeface(Typeface.MONOSPACE);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(source);
        row.addView(bottom);

        parent.addView(row);
    }

    /** Only paths that actually exist are offered; everything else states why it cannot move. */
    private void edit(CheckItem item) {
        if (item == null || actions == null) {
            return;
        }
        switch (item.writePath()) {
            case "edit-target-data":
                promptCountry(item, false);
                break;
            case "hook-payload":
                if ("target.payload.cc_hint".equals(item.id())) {
                    promptCountry(item, true);
                } else {
                    toast(Lang.t("这一项要等目标重新启动后由自检更新",
                            "this item updates when the target next runs"));
                }
                break;
            case "region-apply":
                actions.applyRegion();
                break;
            case "reset-local-identity":
                actions.resetLocalIdentity();
                break;
            case "rerun-target":
                toast(Lang.t("启动目标后重读这一项",
                        "start the target, then this item refreshes"));
                break;
            default:
                toast(Lang.t("没有可用的写入路径", "no write path"));
                break;
        }
    }

    private void promptCountry(CheckItem item, boolean payload) {
        java.util.List<Regions.R> rows = Regions.ALL;
        final java.util.List<String> allNames = new ArrayList<String>();
        final java.util.List<String> allIsos = new ArrayList<String>();
        if (rows == null || rows.isEmpty()) {
            String[] names = {"中国 CN", "新加坡 SG", "美国 US", "日本 JP",
                    "中国台湾 TW", "英国 GB", "澳大利亚 AU"};
            String[] isos = {"CN", "SG", "US", "JP", "TW", "GB", "AU"};
            for (int i = 0; i < names.length; i++) {
                allNames.add(names[i]);
                allIsos.add(isos[i]);
            }
        } else {
            for (Regions.R r : rows) {
                allNames.add(r.name + " · " + r.iso.toUpperCase(Locale.ROOT));
                allIsos.add(r.iso.toUpperCase(Locale.ROOT));
            }
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 8));
        android.widget.EditText search = edit("");
        search.setHint(Lang.t("搜索国家 / ISO", "search country / ISO"));
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));

        final java.util.List<String> shownNames = new ArrayList<String>(allNames);
        final java.util.List<String> shownIsos = new ArrayList<String>(allIsos);
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(this,
                        android.R.layout.simple_list_item_1, shownNames);
        final android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, dp(this, 360));
        listLp.topMargin = dp(this, 6);
        box.addView(list, listLp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e == null ? "" : e.toString().trim().toLowerCase(Locale.ROOT);
                shownNames.clear();
                shownIsos.clear();
                for (int i = 0; i < allNames.size(); i++) {
                    if (q.isEmpty()
                            || allNames.get(i).toLowerCase(Locale.ROOT).contains(q)
                            || allIsos.get(i).toLowerCase(Locale.ROOT).contains(q)) {
                        shownNames.add(allNames.get(i));
                        shownIsos.add(allIsos.get(i));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        final android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                .setTitle(item.id())
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null);
        if (payload) {
            builder.setNeutralButton(Lang.t("用地区值", "use region"), (dialog, which) ->
                    actions.clearPayloadCountryHint());
        } else {
            builder.setNeutralButton(Lang.t("恢复上次", "restore last"), (dialog, which) ->
                    actions.restoreTargetCountryHint());
        }
        final android.app.AlertDialog dialog = builder.create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String iso = shownIsos.get(position);
            if (payload) {
                actions.editPayloadCountryHint(iso);
            } else {
                actions.editTargetCountryHint(iso);
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** One row is the value the target reads; comparisons stay in the model, not the surface. */
    private String valueText(CheckItem item) {
        return displayValue(item);
    }

    private String valueSource(CheckItem item) {
        if ("next-launch".equals(item.note())) {
            return Lang.t("hook 配置 · 下次启动", "hook payload · next launch");
        }
        if ("system-fallback".equals(item.note())) {
            return Lang.t("系统值 · 下次启动", "system value · next launch");
        }
        if ("app-locale".equals(item.note())) {
            return Lang.t("系统应用语言", "system app locale");
        }
        String source = item.source();
        if (item.isStale()) {
            return source + (item.isRead()
                    ? Lang.t(" · 上次自检", " · last self-check")
                    : Lang.t(" · 启动后读取", " · read at next launch"));
        }
        if (!item.isRead()) {
            return source + " · " + coverageLabel(item.coverage());
        }
        if ("not-local".equals(item.value())) {
            return source + Lang.t(" · 仅服务端", " · server only");
        }
        return source;
    }

    private String stateLabel(CheckItem item, RiskModel model) {
        if (item.isStale()) {
            return Lang.t("已过期", "stale");
        }
        if (!item.isRead()) {
            return coverageLabel(item.coverage());
        }
        if (item.verdict() == CheckItem.Verdict.UNKNOWN) {
            int dependency = dependencyReason(item, model);
            if (dependency == DEP_STALE) {
                return Lang.t("已过期", "stale");
            }
            if (dependency == DEP_BOUNDARY) {
                return Lang.t("不可改", "cannot change");
            }
            return Lang.t("关联未定", "unresolved");
        }
        return "";
    }

    private String coverageLabel(CheckItem.Coverage coverage) {
        switch (coverage) {
            case NOT_COVERED:
                return Lang.t("未覆盖", "not covered");
            case NO_RECORD:
                return Lang.t("无记录", "no record");
            case SERVER_ONLY:
                return Lang.t("仅服务端", "server only");
            case STALE:
                return Lang.t("已过期", "stale");
            default:
                return Lang.t("读不到", "unreadable");
        }
    }

    private String displayValue(CheckItem item) {
        if (!item.isRead()) {
            return "—";
        }
        if (Privacy.hidden() && Privacy.sensitiveId(item.id())) {
            return Privacy.STARS;
        }
        String value = item.value();
        if (value.isEmpty()) {
            return Lang.t("空", "empty");
        }
        if ("not-local".equals(value)) {
            return "—";
        }
        if (item.sensitive()) {
            return mask(value);
        }
        return value;
    }

    private static String mask(String value) {
        if (value.length() <= 8) {
            return "…";
        }
        return value.substring(0, 6) + "…" + value.substring(value.length() - 4);
    }



    private String sourceLine(CheckItem item, RiskModel model) {
        String write = writePathText(item.writePath());
        String state = stateLabel(item, model);
        return item.source() + (write.isEmpty() ? "" : " · " + write)
                + (state.isEmpty() || state.equals(write) ? "" : " · " + state);
    }

    /** A direct action is offered only for the paths the Dashboard actually implements. */
    private boolean isEditable(CheckItem item) {
        if (actions == null || item.writable() != CheckItem.Writable.YES) {
            return false;
        }
        return "edit-target-data".equals(item.writePath())
                || ("hook-payload".equals(item.writePath())
                        && "target.payload.cc_hint".equals(item.id()))
                || "reset-local-identity".equals(item.writePath());
    }

    private String writableBadge(CheckItem item) {
        if ("reset-local-identity".equals(item.writePath())
                && !Targets.current().localIdentityWrites()) {
            return Lang.t("锁", "lock");
        }
        if ("reset-local-identity".equals(item.writePath())) {
            // These SDK identifiers are minted as one set; the only honest action is a reset,
            // not a per-field text edit.
            return Lang.t("重置", "reset");
        }
        return isEditable(item) ? Lang.t("改", "edit") : "";
    }

    private int writableColor(CheckItem item) {
        if ("reset-local-identity".equals(item.writePath())
                && !Targets.current().localIdentityWrites()) {
            return MUTE;
        }
        return item.writable() == CheckItem.Writable.YES ? CYAN : MUTE;
    }

    private String writePathText(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        switch (code) {
            case "reset-local-identity":
                return Lang.t("重置本地身份", "reset local identity");
            case "clear-target-data":
                return Lang.t("清目标数据", "clear target data");
            case "edit-target-data":
                return Lang.t("改目标数据", "edit target data");
            case "region-apply":
                return Lang.t("地区写入", "region write");
            case "app-locale-only":
                return Lang.t("仅应用 locale", "app locale only");
            case "hook-only":
                return Lang.t("仅目标 hook", "hook only");
            case "hook-payload":
                return Lang.t("hook 配置", "hook payload");
            case "rerun-target":
                return Lang.t("重开目标", "rerun target");
            case "cannot-change":
                return Lang.t("不可改", "cannot change");
            default:
                return code;
        }
    }

    private static String glyph(CheckItem.Verdict verdict) {
        switch (verdict) {
            case CONSISTENT:
                return "✓";
            case CONFLICT:
                return "✗";
            case BOUNDARY:
                return "锁";
            default:
                return "○";
        }
    }

    private static int ColorOf(CheckItem.Verdict verdict) {
        switch (verdict) {
            case CONSISTENT:
                return GREEN;
            case CONFLICT:
                return RED;
            default:
                return MUTE;
        }
    }

    private static int valueColor(CheckItem item) {
        if (item.verdict() == CheckItem.Verdict.CONFLICT) {
            return RED;
        }
        if (item.verdict() == CheckItem.Verdict.UNKNOWN
                || item.verdict() == CheckItem.Verdict.BOUNDARY) {
            return MUTE;
        }
        return FG;
    }

    private void card(String text, int color) {
        TextView card = txt(text, 11, color);
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        card.setBackground(UiSection.card(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 8);
        card.setLayoutParams(lp);
        root.addView(card);
    }
}
