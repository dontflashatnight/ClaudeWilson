package com.walter.claudewilson;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.model.TargetSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Redesigned Data Cleanup ("数据清理") card matching {@code data_clean_ux_prototype.html}:
 * <ol>
 *   <li><b>3-Tile Telemetry Strip</b>: Private Sandbox (CE/DE/Keystore/external), Browser Session
 *       (domain-scoped Chrome Cookies), and Process &amp; Boundary; tapping any tile expands raw
 *       scanned details inline.</li>
 *   <li><b>Scoped Action Footer</b>: Domain-scoped browser cookie toggle pill + crimson Clear
 *       action with dark confirmation sheet listing only items to be cleared.</li>
 * </ol>
 */
final class DataCleanPanel extends UiSection {
    private static final Map<String, DataClean.Snapshot> CACHE =
            new ConcurrentHashMap<String, DataClean.Snapshot>();
    private static final Map<String, Boolean> BROWSER_PREF =
            new ConcurrentHashMap<String, Boolean>();
    private static volatile String expandedTile;
    private static volatile boolean cleanBusy;
    private static volatile boolean inspectBusy;

    private final BooleanSupplier ssaidHooked;
    private final Runnable onCleaned;

    private LinearLayout cardHost;

    DataCleanPanel(Context context, LinearLayout root, BooleanSupplier ssaidHooked,
                   Runnable onCleaned) {
        super(context);
        this.root = root;
        this.ssaidHooked = ssaidHooked;
        this.onCleaned = onCleaned;
    }

    DataClean.Snapshot currentSnapshot() {
        TargetSpec cur = Targets.current();
        return cur == null ? null : CACHE.get(cur.id());
    }

    View cardView() {
        return cardHost;
    }

    void render() {
        section(Lang.t("数据清理", "Data cleanup", "データ整理"));

        // Unified Card Container
        cardHost = new LinearLayout(this);
        cardHost.setOrientation(LinearLayout.VERTICAL);
        cardHost.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        cardHost.setBackground(UiSection.card(this));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 12);
        cardHost.setLayoutParams(clp);
        root.addView(cardHost);

        repaintCard();

        // Trigger a non-blocking passive scan if no cached snapshot exists for current target
        TargetSpec cur = Targets.current();
        if (!CACHE.containsKey(cur.id()) && RootShell.shellReady()) {
            refreshAsync();
        }
    }

    /** Refreshes sandbox, browser cookie, and boundary telemetry off the UI thread. */
    void refreshAsync() {
        if (inspectBusy || !RootShell.shellReady()) {
            return;
        }
        final TargetSpec target = Targets.current();
        final boolean hooked = ssaidHooked.getAsBoolean();
        inspectBusy = true;
        new Thread(() -> {
            try {
                DataClean.Snapshot snap = DataClean.inspect(getApplicationContext(), target, hooked);
                CACHE.put(target.id(), snap);
                if (!BROWSER_PREF.containsKey(target.id()) && snap.scanned) {
                    BROWSER_PREF.put(target.id(), snap.cookieCount() > 0);
                }
            } catch (Throwable ignored) {
            } finally {
                inspectBusy = false;
                ScreenUpdates.post(screen -> {
                    if (screen.dataClean != null) {
                        screen.dataClean.repaintCard();
                    }
                    screen.refreshPreflight();
                });
            }
        }, "clean-inspect").start();
    }

    void repaintCard() {
        if (cardHost == null) {
            return;
        }
        cardHost.removeAllViews();

        final TargetSpec target = Targets.current();
        final boolean readOnly = !target.localIdentityWrites();
        final DataClean.Snapshot snap = CACHE.get(target.id());
        final boolean scanned = snap != null && snap.scanned;

        cardHost.setAlpha(readOnly ? 0.48f : 1f);

        // =====================================================================
        // LAYER 1: 3-TILE TELEMETRY STRIP (calm informational tones; cleanup is low-frequency)
        // =====================================================================
        String sbVal;
        int sbColor;
        if (readOnly) {
            sbVal = Lang.t("只读", "RO", "読取");
            sbColor = MUTE;
        } else if (!scanned) {
            sbVal = "—";
            sbColor = MUTE;
        } else if (!snap.hasSandboxData()) {
            sbVal = "0 B";
            sbColor = DIM;
        } else {
            sbVal = DataClean.formatBytes(snap.totalSandboxBytes());
            sbColor = FG;
        }

        String brVal;
        int brColor;
        if (readOnly) {
            brVal = "—";
            brColor = MUTE;
        } else if (!scanned) {
            brVal = "—";
            brColor = MUTE;
        } else if (!snap.rootMode) {
            brVal = Lang.t("需 root", "root", "要root");
            brColor = MUTE;
        } else if (snap.cookieCount() > 0) {
            brVal = snap.cookieCount() + Lang.t(" 项", " hits", " 件");
            brColor = FG;
        } else {
            brVal = Lang.t("0 项", "0 hits", "0 件");
            brColor = DIM;
        }

        String bdVal;
        int bdColor;
        if (readOnly) {
            bdVal = "—";
            bdColor = MUTE;
        } else if (!scanned) {
            bdVal = "—";
            bdColor = MUTE;
        } else if (snap.targetRunning) {
            bdVal = Lang.t("运行中", "Running", "実行中");
            bdColor = AMBER;
        } else if (snap.boundaryCount() > 0) {
            bdVal = snap.boundaryCount() + Lang.t(" 项边界", " bounds", " 境界");
            bdColor = DIM;
        } else {
            bdVal = Lang.t("已停止", "Idle", "停止中");
            bdColor = DIM;
        }

        LinearLayout tri = new LinearLayout(this);
        tri.setOrientation(LinearLayout.HORIZONTAL);
        tri.addView(telemetryTile("sandbox", Lang.t("私有沙盒", "Sandbox", "サンドボックス"),
                sbVal, sbColor, 0, dp(this, 6)));
        tri.addView(telemetryTile("browser", Lang.t("浏览器会话", "Browser", "ブラウザ"),
                brVal, brColor, 0, dp(this, 6)));
        tri.addView(telemetryTile("boundary", Lang.t("进程与跨界", "Process", "プロセス"),
                bdVal, bdColor, 0, 0));
        cardHost.addView(tri);

        // Inline Expandable Detail Drawer
        if (expandedTile != null) {
            cardHost.addView(buildDrawer(expandedTile, target, readOnly, snap, scanned));
        }

        // Divider
        View div = new View(this);
        div.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(this, 1));
        dlp.topMargin = dp(this, 10);
        dlp.bottomMargin = dp(this, 9);
        div.setLayoutParams(dlp);
        cardHost.addView(div);

        // =====================================================================
        // LAYER 2: SCOPED ACTION FOOTER
        // =====================================================================
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        final int cookieCount = scanned ? snap.cookieCount() : 0;
        final boolean canToggleBrowser = !readOnly && scanned && snap.rootMode && cookieCount > 0;
        final boolean browserOn = canToggleBrowser
                && Boolean.TRUE.equals(BROWSER_PREF.getOrDefault(target.id(), true));

        LinearLayout scopeHolder = new LinearLayout(this);
        scopeHolder.setOrientation(LinearLayout.HORIZONTAL);
        scopeHolder.setGravity(Gravity.CENTER_VERTICAL);
        scopeHolder.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        View togglePill = buildBrowserToggle(browserOn, canToggleBrowser, cookieCount,
                scanned && !snap.rootMode, target);
        scopeHolder.addView(togglePill);
        footer.addView(scopeHolder);

        TextView clearBtn = buildClearButton(readOnly, scanned, snap, target, browserOn);
        footer.addView(clearBtn);

        cardHost.addView(footer);
    }

    private View telemetryTile(final String id, String label, String val,
                               int valColor, int leftM, int rightM) {
        boolean selected = id.equals(expandedTile);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(this, 10), dp(this, 9), dp(this, 10), dp(this, 9));
        tile.setBackground(roundBox(selected ? 0xFF19282C : CARD2, selected ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftM;
        lp.rightMargin = rightM;
        tile.setLayoutParams(lp);

        TextView lbl = txt(label, 9.5f, DIM);
        lbl.setSingleLine(true);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(lbl);

        TextView v = txt(val, 11, valColor);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, dp(this, 4), 0, 0);
        tile.addView(v);

        bindPress(tile);
        tile.setOnClickListener(view -> {
            expandedTile = selected ? null : id;
            repaintCard();
        });
        return tile;
    }

    private View buildDrawer(String id, TargetSpec target, boolean readOnly,
                             DataClean.Snapshot snap, boolean scanned) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        box.setBackground(roundBox(CARD2, 0xFF2B454B, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String heading = "sandbox".equals(id)
                ? Lang.t("私有沙盒", "Sandbox", "サンドボックス")
                : "browser".equals(id)
                ? Lang.t("浏览器会话", "Browser", "ブラウザ")
                : Lang.t("进程与跨界", "Process", "プロセス");
        TextView ht = txt(heading, 9.5f, CYAN);
        ht.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ht.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(ht);

        TextView close = txt("✕", 10, MUTE);
        close.setPadding(dp(this, 8), dp(this, 1), dp(this, 2), dp(this, 1));
        close.setOnClickListener(v -> {
            expandedTile = null;
            repaintCard();
        });
        head.addView(close);
        box.addView(head);

        List<String[]> rows = new ArrayList<String[]>();
        List<Integer> colors = new ArrayList<Integer>();

        if (readOnly) {
            addRow(rows, colors, Lang.t("当前目标策略", "Target policy"),
                    Lang.t("禁止数据写入", "Read-only"), MUTE);
        } else if (!scanned) {
            addRow(rows, colors, Lang.t("状态", "Status"),
                    RootShell.shellReady() ? Lang.t("扫描中…", "Scanning…")
                            : Lang.t("需要 root 或 Shizuku", "Needs root/Shizuku"), MUTE);
        } else if ("sandbox".equals(id)) {
            if (snap.rootMode) {
                addRow(rows, colors, "CE /data/user/...", DataClean.formatBytes(snap.ceBytes), DIM);
                addRow(rows, colors, "DE /data/user_de/...", DataClean.formatBytes(snap.deBytes), DIM);
                addRow(rows, colors, "Keystore2", Lang.t("随 clear 重置", "Reset by clear"), GREEN);
            } else {
                addRow(rows, colors, Lang.t("私有沙盒", "Private sandbox"),
                        Lang.t("由 pm clear 清空", "Cleared by pm clear"), DIM);
            }
            String extVal = snap.extFiles > 0
                    ? DataClean.formatBytes(snap.extBytes) + " · " + snap.extFiles + Lang.t(" 文件", " files")
                    : Lang.t("0 文件", "0 files");
            if (snap.legacyResidueCount > 0) {
                extVal += " (" + snap.legacyResidueCount + Lang.t(" 旧残留", " legacy") + ")";
            }
            addRow(rows, colors, Lang.t("外部 Android/data", "External Android/data"),
                    extVal, snap.extFiles > 0 ? FG : DIM);
            if (snap.cwBackupCount > 0) {
                addRow(rows, colors, "CW backup/" + target.id(),
                        snap.cwBackupCount + Lang.t(" 份过期", " stale"), DIM);
            }
        } else if ("browser".equals(id)) {
            if (!snap.rootMode) {
                addRow(rows, colors, "Chrome Cookies", Lang.t("需 root 权限扫描", "Requires root"), MUTE);
            } else if (snap.browserCookies.isEmpty()) {
                addRow(rows, colors, formatDomains(target.cookieDomains()),
                        Lang.t("0 项命中", "0 hits"), DIM);
            } else {
                Map<String, List<String>> byHost = new LinkedHashMap<String, List<String>>();
                for (DataClean.CookieHit hit : snap.browserCookies) {
                    List<String> list = byHost.get(hit.host);
                    if (list == null) {
                        list = new ArrayList<String>();
                        byHost.put(hit.host, list);
                    }
                    list.add(hit.name);
                }
                for (Map.Entry<String, List<String>> e : byHost.entrySet()) {
                    List<String> names = e.getValue();
                    StringBuilder preview = new StringBuilder();
                    int lim = Math.min(names.size(), 3);
                    for (int i = 0; i < lim; i++) {
                        if (i > 0) preview.append(", ");
                        preview.append(names.get(i));
                    }
                    if (names.size() > 3) preview.append("…");
                    addRow(rows, colors, e.getKey() + " · " + preview,
                            names.size() + Lang.t(" 项", " cookies"), FG);
                }
                if (snap.chromeRunning) {
                    addRow(rows, colors, Lang.t("Chrome 常驻进程", "Chrome running"),
                            Lang.t("清理前强停释锁", "Force-stopped first"), DIM);
                }
            }
        } else {
            addRow(rows, colors, Lang.t("目标进程 + netd DNS", "Process + netd DNS"),
                    snap.targetRunning ? Lang.t("运行中 · 将强停刷新", "Running · will flush")
                            : Lang.t("未运行 · 清理时刷新", "Idle · flushes on clean"),
                    snap.targetRunning ? AMBER : DIM);
            if (snap.accountAuthCount > 0) {
                addRow(rows, colors, Lang.t("AccountManager 授权", "AccountManager grants"),
                        snap.accountAuthCount + Lang.t(" 项 · 不碰 GMS", " · GMS untouched"), DIM);
            }
            if (snap.hasBlockStoreMarker) {
                addRow(rows, colors, "GMS Block Store",
                        Lang.t("跨清除恢复令牌", "Cross-clear token"), DIM);
            }
            addRow(rows, colors, "SSAID (ANDROID_ID)",
                    snap.ssaidHooked ? Lang.t("已由人设 Hook 接管", "Hooked by persona")
                            : Lang.t("未开空间为真值", "Raw until persona on"),
                    snap.ssaidHooked ? GREEN : AMBER);
            if (snap.allowBackup) {
                addRow(rows, colors, "ALLOW_BACKUP=true",
                        snap.backupManagerEnabled ? Lang.t("系统云备份已开", "Cloud backup ON")
                                : Lang.t("本机云备份已关", "Cloud backup OFF"),
                        snap.backupManagerEnabled ? AMBER : GREEN);
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            String[] pair = rows.get(i);
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(0x661E3136);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 1);
                slp.topMargin = dp(this, 4);
                slp.bottomMargin = dp(this, 4);
                sep.setLayoutParams(slp);
                box.addView(sep);
            } else {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(this, 6)));
                box.addView(gap);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView k = txt(pair[0], 9.5f, FG);
            k.setTypeface(Typeface.MONOSPACE);
            k.setSingleLine(true);
            k.setEllipsize(TextUtils.TruncateAt.END);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(k);

            TextView v = txt(pair[1], 9.5f, colors.get(i));
            v.setTypeface(Typeface.MONOSPACE);
            v.setSingleLine(true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = dp(this, 8);
            v.setLayoutParams(vlp);
            row.addView(v);
            box.addView(row);
        }

        return box;
    }

    private View buildBrowserToggle(final boolean on, final boolean enabled, int cookieCount,
                                    boolean nonRoot, final TargetSpec target) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(this, 9), dp(this, 5), dp(this, 10), dp(this, 5));
        pill.setBackground(roundBox(
                on ? 0x172EC4B6 : CARD2,
                on ? 0x732EC4B6 : STROKE, 14));
        if (!enabled) {
            pill.setAlpha(0.45f);
        }

        TextView check = txt(on ? "✓" : "", 8, 0xFF041918);
        check.setTypeface(Typeface.DEFAULT_BOLD);
        check.setGravity(Gravity.CENTER);
        int sz = dp(this, 13);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(sz, sz);
        clp.rightMargin = dp(this, 6);
        check.setLayoutParams(clp);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(on ? CYAN : 0x00000000);
        cd.setStroke(dp(this, 1), on ? CYAN : MUTE);
        check.setBackground(cd);
        pill.addView(check);

        String label = nonRoot
                ? Lang.t("浏览器会话需 root", "Browser needs root")
                : cookieCount > 0
                ? Lang.t("含浏览器会话 (", "Include browser (") + cookieCount + ")"
                : Lang.t("无浏览器会话", "No browser cookies");
        TextView tv = txt(label, 9.5f, on ? FG : DIM);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(tv);

        if (enabled) {
            bindPress(pill);
            pill.setOnClickListener(v -> {
                BROWSER_PREF.put(target.id(), !on);
                repaintCard();
            });
        }
        return pill;
    }

    private TextView buildClearButton(final boolean readOnly, boolean scanned,
                                      final DataClean.Snapshot snap,
                                      final TargetSpec target, final boolean browserOn) {
        String label;
        int fg;
        int fill;
        int stroke;
        if (cleanBusy) {
            label = Lang.t("清理中…", "Cleaning…", "消去中…");
            fg = AMBER;
            fill = 0x22E5A93B;
            stroke = AMBER;
        } else if (readOnly) {
            label = Lang.t("不可写", "RO", "不可");
            fg = MUTE;
            fill = CARD2;
            stroke = STROKE;
        } else if (scanned && snap.isClean()) {
            label = Lang.t("复清", "Reclean", "再消去");
            fg = MUTE;
            fill = CARD2;
            stroke = STROKE;
        } else {
            label = Lang.t("清除", "Clear", "消去");
            fg = RED;
            fill = 0x24E55353;
            stroke = RED;
        }
        TextView btn = pillText(label, 10.5f, fg, fill, stroke);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(this, 8);
        btn.setLayoutParams(lp);
        if (!cleanBusy) {
            bindPress(btn);
            btn.setOnClickListener(v -> {
                if (readOnly) {
                    Toast.makeText(this, Lang.t("此目标目前只读诊断，不做数据写入",
                            "this target is diagnostic-only", "この対象は読取専用"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                showConfirmDialog(target, snap, browserOn);
            });
        }
        return btn;
    }

    // =========================================================================
    // DARK CONFIRMATION SHEET (ONLY ITEMS TO BE CLEARED — NO BOUNDARY BLOCK)
    // =========================================================================

    private void showConfirmDialog(final TargetSpec target, final DataClean.Snapshot snap,
                                   final boolean cleanBrowser) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 14));

        // Header: 清除数据 + package pill
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt(Lang.t("清除数据", "Clear data", "データを消去"), 15, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(title);

        TextView pkgPill = pillText(target.packageName(), 9.5f, DIM, CARD2, STROKE);
        pkgPill.setTypeface(Typeface.MONOSPACE);
        head.addView(pkgPill);
        box.addView(head);

        // Build exact list of items to be cleared
        List<String[]> items = new ArrayList<String[]>();
        String sbSize = (snap != null && snap.scanned)
                ? DataClean.formatBytes(snap.totalSandboxBytes())
                : Lang.t("全量", "All");
        items.add(new String[]{
                Lang.t("目标沙盒 (CE + DE + Keystore2)", "Sandbox (CE + DE + Keystore2)"),
                sbSize
        });
        items.add(new String[]{
                Lang.t("外部 Android/{data,obb,media}", "External Android/{data,obb,media}"),
                Lang.t("全用户清空", "All users")
        });
        items.add(new String[]{
                Lang.t("强停目标进程 + 刷新 netd DNS", "Force-stop + netd DNS flush"),
                Lang.t("内存缓存", "RAM cache")
        });
        if (snap != null && snap.cwBackupCount > 0) {
            items.add(new String[]{
                    "/data/local/tmp/claudewilson/backup",
                    snap.cwBackupCount + Lang.t(" 份临时备份", " backups")
            });
        }
        if (cleanBrowser && snap != null && snap.cookieCount() > 0) {
            items.add(new String[]{
                    "Chrome " + formatDomains(target.cookieDomains()),
                    snap.cookieCount() + Lang.t(" 项 Cookie", " cookies")
            });
        }

        // Single clean "将清除" container (no boundary block)
        LinearLayout grp = new LinearLayout(this);
        grp.setOrientation(LinearLayout.VERTICAL);
        grp.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        grp.setBackground(roundBox(CARD2, STROKE, 12));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(this, 12);
        grp.setLayoutParams(glp);

        LinearLayout gh = new LinearLayout(this);
        gh.setOrientation(LinearLayout.HORIZONTAL);
        TextView gl = txt(Lang.t("将清除", "Will clear", "消去対象"), 9.5f, DIM);
        gl.setTypeface(Typeface.DEFAULT_BOLD);
        gl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        gh.addView(gl);
        TextView gc = txt(items.size() + Lang.t(" 项", " items", " 件"), 9.5f, DIM);
        gc.setTypeface(Typeface.MONOSPACE);
        gh.addView(gc);
        grp.addView(gh);

        for (int i = 0; i < items.size(); i++) {
            String[] pair = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, i == 0 ? dp(this, 8) : dp(this, 6), 0, 0);

            TextView k = txt(pair[0], 10, FG);
            k.setTypeface(Typeface.MONOSPACE);
            k.setSingleLine(true);
            k.setEllipsize(TextUtils.TruncateAt.END);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(k);

            TextView v = txt(pair[1], 10, DIM);
            v.setTypeface(Typeface.MONOSPACE);
            v.setSingleLine(true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = dp(this, 8);
            v.setLayoutParams(vlp);
            row.addView(v);
            grp.addView(row);
        }
        box.addView(grp);

        // Action Buttons
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(this, 16), 0, 0);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        TextView cancel = txt(Lang.t("取消", "Cancel", "キャンセル"), 11.5f, DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(roundBox(CARD2, STROKE, 22));
        cancel.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams cw = new LinearLayout.LayoutParams(0, -2, 1);
        cw.rightMargin = dp(this, 6);
        cancel.setLayoutParams(cw);
        bindPress(cancel);
        cancel.setOnClickListener(v -> dialog.dismiss());

        TextView confirm = txt(Lang.t("确认清除", "Clear", "消去する"), 11.5f, RED);
        confirm.setGravity(Gravity.CENTER);
        confirm.setTypeface(Typeface.DEFAULT_BOLD);
        confirm.setBackground(roundBox(0xFF3A1218, RED, 22));
        confirm.setPadding(0, dp(this, 9), 0, dp(this, 9));
        LinearLayout.LayoutParams dw = new LinearLayout.LayoutParams(0, -2, 1);
        dw.leftMargin = dp(this, 6);
        confirm.setLayoutParams(dw);
        bindPress(confirm);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (RootShell.state() != RootShell.ST_READY) {
                Toast.makeText(this, Lang.t("需要 root 或 Shizuku",
                        "needs root or Shizuku", "root または Shizuku が必要"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            executeClean(target, cleanBrowser);
        });

        buttons.addView(cancel);
        buttons.addView(confirm);
        box.addView(buttons);

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundBox(CARD, 0xFF2B454B, 20));
            window.setDimAmount(0.68f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 48),
                    dp(this, 420));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void executeClean(final TargetSpec target, final boolean cleanBrowser) {
        if (cleanBusy) return;
        cleanBusy = true;
        expandedTile = null;
        repaintCard();
        final String script = DataClean.cleanCommand(getApplicationContext(), target, cleanBrowser);
        new Thread(() -> {
            String out = RootShell.runCapture(script);
            ClaudeRiskCollector.invalidateCache();
            ChatGptRiskCollector.invalidateCache();
            final boolean ok = DataClean.ok(out);
            final boolean hooked = ssaidHooked.getAsBoolean();
            DataClean.Snapshot after = DataClean.inspect(getApplicationContext(), target, hooked);
            CACHE.put(target.id(), after);
            BROWSER_PREF.put(target.id(), after.cookieCount() > 0);
            cleanBusy = false;
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen, ok
                                ? Lang.t("已清除并校验归零", "Cleared & verified", "消去・検証完了")
                                : Lang.t("部分未清除，请查看审计", "Partly failed, see audit",
                                        "一部失敗（監査を参照）"),
                        Toast.LENGTH_LONG).show();
                Audit.record(screen, "clean", (ok ? "ok " : "failed ") + out);
                if (screen.dataClean != null) {
                    screen.dataClean.repaintCard();
                }
                if (onCleaned != null) {
                    onCleaned.run();
                }
            });
        }, "data-clean").start();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static void addRow(List<String[]> rows, List<Integer> colors,
                               String k, String v, int color) {
        rows.add(new String[]{k, v});
        colors.add(color);
    }

    private static String formatDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < domains.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append('.').append(domains.get(i));
        }
        return sb.toString();
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
        t.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
        t.setBackground(roundBox(fill, stroke, 10));
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
