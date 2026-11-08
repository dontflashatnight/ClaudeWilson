package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import java.util.function.Supplier;

/** Egress controls and presentation; probe execution and assessment are separate. */
final class NetworkPanel extends UiSection {
    private final Supplier<String> expectedRegion;
    private TextView netLog;
    /** Holds the compared values as columns; the prose log below keeps the detail. */
    private LinearLayout netTable;
    private static volatile String netOut = "";
    private static volatile NetworkAssessment.Result lastResult;
    private static volatile boolean netBusy;
    private android.widget.EditText vpnEdit;
    private TextView fixLine;
    /** Current VPN package/label, read locally on every render; empty when no tunnel is up. */
    private String vpnNowPkg = "";
    private String vpnNowLabel = "";
    private boolean vpnFromShell;
    private volatile boolean vpnShellBusy;
    private long vpnShellAt;
    /** Local always-on VPN setting; readable without a probe and shown in the always-on row. */
    private String alwaysOnNowPkg = "";
    private boolean alwaysOnNowReadable;
    /** One callback for the VPN transport; the card must not wait for a manual scan. */
    private boolean vpnWatching;
    private android.net.ConnectivityManager.NetworkCallback vpnCallback;
    /** Last package this panel filled in itself, so a user edit is never overwritten. */
    private String autoVpn = "";
    /** Package behind the label shown in the field; the fix needs it, the label is for reading. */
    private String vpnPkg = "";

    NetworkPanel(Context context, LinearLayout root, Supplier<String> expectedRegion) {
        super(context);
        this.root = root;
        this.expectedRegion = expectedRegion;
    }

    void render() {
        section(Lang.t("网络", "Network", "ネットワーク"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        card.setBackground(UiSection.card(this));
        netLog = txt(netOut.isEmpty()
                ? Lang.t("从 shell 侧检测；本 app 没有 INTERNET 权限",
                        "checked from the shell; this app has no INTERNET permission",
                        "shell 側で確認。本 app は INTERNET 無し")
                : netOut, 10, DIM);
        netLog.setTypeface(Typeface.MONOSPACE);
        netTable = new LinearLayout(this);
        netTable.setOrientation(LinearLayout.VERTICAL);
        netTable.setPadding(0, 0, 0, dp(this, 6));
        card.addView(netTable);
        // A VPN can be up without a probe: read the local transport state and always show it.
        // The full egress table still needs the button because it opens sockets.
        readVpnNow();
        watchVpn();
        if (vpnNowLabel.isEmpty()) probeVpnShell();
        // The prose log is not attached: the comparison table already carries the result.
        renderTable(lastResult);
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setPadding(0, dp(this, 8), 0, 0);
        TextView bRun = netBusy ? chip("…", CYAN)
                : primaryChip("scan",
                        CYAN, 0xFF04120F);
        // Empty unless a VPN is actually up: the field names the running VPN, it is not a
        // remembered setting. A user-typed value is kept across checks (autoVpn tracks ours).
        String knownVpn = lastResult != null && lastResult.vpn != null
                ? lastResult.vpn : vpnNowPkg;
        vpnPkg = knownVpn;
        autoVpn = knownVpn.isEmpty() ? ""
                : lastResult != null ? appLabel(knownVpn) : vpnNowLabel;
        vpnEdit = edit(autoVpn);
        // Display only: the fix needs the package, the label is what a person recognises.
        vpnEdit.setFocusable(false);
        vpnEdit.setClickable(false);
        // Empty means "no VPN is up", so the dim hint says exactly that instead of naming a field.
        vpnEdit.setHint(Lang.t("VPN 未建立", "VPN not up", "VPN 未接続"));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1.7f);
        vp.leftMargin = dp(this, 6); vp.rightMargin = dp(this, 6);
        vpnEdit.setLayoutParams(vp);
        TextView bVpn = chip(Lang.t("VPN 设置", "VPN settings", "VPN 設定"), FG);
        bRun.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.15f));
        bVpn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.15f));
        r1.addView(bRun); r1.addView(vpnEdit);
        r1.addView(bVpn);
        card.addView(r1);
        // One verdict line for the fix, so the button reports a result instead of dumping the
        // command's raw output into the log area.
        fixLine = txt("", 10, DIM);
        fixLine.setTypeface(Typeface.MONOSPACE);
        fixLine.setPadding(0, dp(this, 6), 0, 0);
        fixLine.setVisibility(View.GONE);
        card.addView(fixLine);
        // One button for both halves: it writes the fix, reads the result back and re-scans, so a
        // tap ends with a fresh table instead of two buttons that could disagree.
        bRun.setOnClickListener(v -> {
            if (RootShell.state() != RootShell.ST_READY || vpnPkg == null
                    || !vpnPkg.matches("[A-Za-z0-9_.]+")) {
                netProbe();
                return;
            }
            netFix(vpnPkg);
        });
        bVpn.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_VPN_SETTINGS));
            } catch (Throwable t) {
                netOut = "vpn settings?"; netLog.setText(netOut);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(this, 12);
        card.setLayoutParams(clp);
        root.addView(card);
    }

    /**
     * The egress probe opens sockets with multi-second timeouts. It is deliberately not part of
     * the scan or the region transaction: the card's button is the only trigger, so a switch is
     * never queued behind a network read.
     */
    private void netProbe() {
        if (netBusy) return;
        if (RootShell.state() != RootShell.ST_READY) {
            netOut = Lang.t("需要 root 或 Shizuku", "needs root or Shizuku",
                    "root または Shizuku が必要");
            netLog.setText(netOut); return;
        }
        netBusy = true; netOut = "…"; netLog.setText(netOut);
        new Thread(() -> {
            com.walter.claudewilson.model.TargetSpec target = Targets.current();
            String raw = NetworkActions.probe(getPackageName(), target);
            NetworkAssessment.Result result = NetworkAssessment.evaluate(
                    raw, getSharedPreferences("apply", 0), expectedRegion.get(),
                    target.displayName());
            netBusy = false; lastResult = result; netOut = compact(result);
            ScreenUpdates.post(screen -> screen.network.renderTable(result));
        }).start();
    }

    /** Drop rows already shown in the table; keep only UDP/IP-flip and fix hints. */
    private static String compact(NetworkAssessment.Result result) {
        StringBuilder out = new StringBuilder();
        for (String line : result.text.split("\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if ((t.startsWith("✓ ") || t.startsWith("✗ ") || t.startsWith("⚠ ")
                    || t.startsWith("— "))
                    && (t.contains("出口") || t.contains("egress")
                    || t.contains("ASN") || t.contains("always-on VPN")
                    || t.contains("始终开启 VPN") || t.contains("dns·")
                    || t.contains(" v6 ") || t.contains("域→"))) {
                continue;
            }
            out.append(t).append('\n');
        }
        return out.toString().trim();
    }

    /** Local, no-socket VPN detection: VPN transport first, then a tun/ppp/wg interface. */
    private void readVpnNow() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
                int uid = nc.getOwnerUid();
                if (uid >= 0) {
                    String[] pkgs = getPackageManager().getPackagesForUid(uid);
                    if (pkgs != null && pkgs.length > 0) {
                        vpnNowPkg = pkgs[0];
                        vpnNowLabel = appLabel(pkgs[0]);
                    }
                }
                break;
            }
        } catch (Throwable ignored) {
        }
        if (!vpnNowLabel.isEmpty()) return;
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("tun") || n.startsWith("ppp")
                        || n.startsWith("wg") || n.startsWith("utun")) {
                    vpnNowLabel = n;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Per-app VPNs (Clash is one) are hidden from an app that is not in the tunnel's allowed
     * list: ConnectivityManager and NetworkInterface both report nothing. dumpsys is the
     * only reliable read. It runs off the UI thread and only when the local read was empty.
     */
    private void probeVpnShell() {
        if (vpnShellBusy || !vpnNowLabel.isEmpty()
                || RootShell.state() != RootShell.ST_READY) return;
        long now = System.currentTimeMillis();
        if (now - vpnShellAt < 4000) return;
        vpnShellAt = now;
        vpnShellBusy = true;
        new Thread(() -> {
            String out = RootShell.run(
                    "D=$(dumpsys connectivity 2>/dev/null"
                    + " | grep -o 'VPN:[A-Za-z0-9_.]*' | head -1 | cut -d: -f2);"
                    + " if [ -z \"$D\" ]; then"
                    + " D=$(ip -o link 2>/dev/null | grep -oE '(tun|ppp|wg)[0-9]+' | head -1);"
                    + " fi; echo \"$D\"", 128);
            String value = "";
            for (String l : out.split("\n")) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("SU_RC") || l.startsWith("EXC")) continue;
                value = l;
                break;
            }
            final String v = value;
            vpnShellBusy = false;
            ScreenUpdates.post(screen -> {
                if (screen.network != null) screen.network.applyVpnShell(v);
            });
        }).start();
    }

    /** UI-thread result of the shell read; a package or an interface name, or empty. */
    void applyVpnShell(String value) {
        if (value == null || value.isEmpty()) {
            if (vpnFromShell) {
                vpnFromShell = false;
                vpnNowPkg = "";
                vpnNowLabel = "";
            }
        } else {
            vpnFromShell = true;
            if (value.indexOf('.') > 0) {
                vpnNowPkg = value;
                vpnNowLabel = appLabel(value);
            } else {
                vpnNowPkg = "";
                vpnNowLabel = value;
            }
        }
        syncVpnField();
        renderTable(lastResult);
    }

    private void syncVpnField() {
        if (vpnEdit == null) return;
        String current = vpnEdit.getText().toString();
        if (current.isEmpty() || current.equals(autoVpn)) vpnEdit.setText(vpnNowLabel);
        autoVpn = vpnNowLabel;
        vpnPkg = vpnNowPkg;
    }

    private void watchVpn() {
        if (vpnWatching) return;
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            vpnCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) { refreshAsync(); }
                @Override public void onLost(android.net.Network n) { refreshAsync(); }
                @Override public void onCapabilitiesChanged(
                        android.net.Network n, android.net.NetworkCapabilities c) {
                    refreshAsync();
                }
            };
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    .build(), vpnCallback);
            vpnWatching = true;
        } catch (Throwable ignored) {
        }
    }

    private void refreshAsync() {
        ScreenUpdates.post(screen -> {
            if (screen.network != null) screen.network.refreshVpn();
        });
    }

    /** Re-read the tunnel and repaint only the table; safe to call from a network callback. */
    void refreshVpn() {
        readVpnNow();
        if (vpnNowLabel.isEmpty()) probeVpnShell();
        if (vpnEdit != null) {
            String current = vpnEdit.getText().toString();
            if (current.isEmpty() || current.equals(autoVpn)) vpnEdit.setText(vpnNowLabel);
            autoVpn = vpnNowLabel;
            vpnPkg = vpnNowPkg;
        }
        renderTable(lastResult);
    }

    void release() {
        if (vpnWatching && vpnCallback != null) {
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(vpnCallback);
            } catch (Throwable ignored) {
            }
        }
        vpnWatching = false;
        vpnCallback = null;
    }

    private void readAlwaysOnNow() {
        try {
            String app = android.provider.Settings.Secure.getString(
                    getContentResolver(), "always_on_vpn_app");
            alwaysOnNowReadable = true;
            alwaysOnNowPkg = app == null || "null".equals(app) ? "" : app;
        } catch (Throwable t) {
            alwaysOnNowReadable = false;
            alwaysOnNowPkg = "";
        }
    }

    private void vpnHeader() {
        netTable.addView(row3(Lang.t("字段", "field", "項目"),
                Lang.t("期望", "expected", "期待"),
                Lang.t("实际", "actual", "実際"), -2));
        netTable.addView(row3("VPN", Lang.t("已建立", "up", "接続済み"),
                vpnNowLabel.isEmpty()
                        ? Lang.t("未建立", "not up", "未接続") : vpnNowLabel,
                vpnNowLabel.isEmpty() ? 0 : 1));
    }

    private void rowAlwaysOn(NetworkAssessment.Result result) {
        boolean readable;
        boolean on;
        if (result != null) {
            readable = true;
            on = result.alwaysOn != null && !result.alwaysOn.isEmpty();
        } else {
            readAlwaysOnNow();
            readable = alwaysOnNowReadable;
            on = !alwaysOnNowPkg.isEmpty();
        }
        netTable.addView(row3(Lang.t("常开 VPN", "always-on", "常時 VPN"),
                Lang.t("已开启", "on", "ON"),
                !readable ? "—" : on
                        ? Lang.t("已开启", "on", "ON")
                        : Lang.t("未开启", "off", "OFF"),
                !readable ? -1 : on ? 1 : 0));
    }

    private String metric(int state) {
        return state == 1 ? Lang.t("未泄露", "not leaked", "漏洩なし")
                : state == 0 ? Lang.t("泄露", "leaked", "漏洩")
                : Lang.t("未查询到", "unread", "未観測");
    }

    /**
     * Always paints the full row set. Before the first/next probe the value columns read "—";
     * the rows themselves must not disappear, otherwise the card looks empty and a tunnel/VPN
     * change is only discovered by pressing a button.
     */
    private void renderTable(NetworkAssessment.Result result) {
        if (netTable == null) return;
        netTable.removeAllViews();
        readVpnNow();
        if (vpnNowLabel.isEmpty() && result != null
                && result.vpn != null && !result.vpn.isEmpty()) {
            vpnNowPkg = result.vpn;
            vpnNowLabel = appLabel(result.vpn);
        }
        vpnHeader();
        String region = expectedRegion.get() == null ? "-" : expectedRegion.get();
        if (result == null) {
            netTable.addView(row3(Lang.t("IP 出口", "IP exit", "IP 出口"),
                    region, "—", -1));
            netTable.addView(row3(Lang.t("线路", "line", "回線"),
                    Lang.t("家宽", "residential", "一般回線"), "—", -1));
            netTable.addView(row3("DNS", Lang.t("未泄露", "not leaked", "漏洩なし"),
                    "—", -1));
            netTable.addView(row3("IPv6", Lang.t("未泄露", "not leaked", "漏洩なし"),
                    "—", -1));
            netTable.addView(row3("UDP", Lang.t("同出口", "same egress", "同一出口"),
                    "—", -1));
            netTable.addView(row3(Lang.t("节点", "node", "ノード"),
                    Lang.t("命中规则", "rule hit", "ルール一致"), "—", -1));
            rowAlwaysOn(null);
            syncVpnField();
            return;
        }
        netTable.addView(row3(Lang.t("IP 出口", "IP exit", "IP 出口"),
                region,
                Privacy.mask((result.egIp.isEmpty() ? "?" : result.egIp) + " " + result.egCc),
                result.egState));
        netTable.addView(row3(Lang.t("线路", "line", "回線"),
                Lang.t("家宽", "residential", "一般回線"),
                result.egOrg.isEmpty() ? metric(-1)
                        : result.egDatacenter
                                ? Lang.t("机房 IP", "datacenter IP", "データセンター IP")
                                : Lang.t("住宅 IP", "residential IP", "住宅 IP"),
                result.egOrg.isEmpty() ? -1 : result.egDatacenter ? 0 : 1));
        netTable.addView(row3("DNS", Lang.t("未泄露", "not leaked", "漏洩なし"),
                metric(result.dnsState), result.dnsState));
        netTable.addView(row3("IPv6", Lang.t("未泄露", "not leaked", "漏洩なし"),
                metric(result.v6State), result.v6State));
        netTable.addView(row3("UDP", Lang.t("同出口", "same egress", "同一出口"),
                metric(result.udpState), result.udpState));
        netTable.addView(row3(Lang.t("节点", "node", "ノード"),
                Lang.t("命中规则", "rule hit", "ルール一致"),
                result.tgtState == 1 ? Lang.t("已命中", "hit", "一致")
                        : metric(result.tgtState),
                result.tgtState));
        rowAlwaysOn(result);
        String log = compact(result);
        netLog.setText(log);
        netLog.setVisibility(log.isEmpty() ? View.GONE : View.VISIBLE);
        String v = result.vpn;
        // Follow the running VPN: fill it in when one is up, clear it when none is. A value the
        // user typed is left alone unless it is still the one this panel wrote.
        if (vpnEdit != null) {
            String running = v == null ? "" : v;
            if (running.isEmpty()) running = vpnNowPkg;
            String label = running.isEmpty() ? vpnNowLabel : appLabel(running);
            vpnPkg = running;
            String current = vpnEdit.getText().toString();
            if (current.isEmpty() || current.equals(autoVpn)) vpnEdit.setText(label);
            autoVpn = label;
        }
    }

    /** Application label for a package, or the package itself when it cannot be resolved. */
    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null || label.length() == 0 ? pkg : label.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Runs the fix, then reports one verdict and re-checks the card. */
    private void netFix(String pkg) {
        if (RootShell.state() != RootShell.ST_READY) {
            showFix(Lang.t("需要 root 或 Shizuku 才能改系统设置",
                    "needs root or Shizuku to change system settings",
                    "システム設定の変更には root または Shizuku が必要"), RED);
            return;
        }
        showFix(Lang.t("修复中…", "fixing…", "修復中…"), DIM);
        new Thread(() -> {
            String out = RootShell.runCapture(NetworkActions.fixCommand(pkg));
            String proxy = token(out, "proxy=");
            String v6 = token(out, "v6=");
            String v6addr = token(out, "v6addr=");
            // Off means both: the sysctl flag and no remaining global v6 address.
            boolean v6Ok = "1".equals(v6) && "0".equals(v6addr == null ? "" : v6addr.trim());
            boolean proxyOk = proxy == null || proxy.isEmpty() || "null".equals(proxy);
            String text = tick(v6Ok) + (v6Ok
                            ? Lang.t("IPv6 已关闭", "IPv6 off", "IPv6 無効")
                            : Lang.t("IPv6 仍开启（需 root）", "IPv6 still on (needs root)",
                                    "IPv6 有効（root が必要）"))
                    + "\n" + tick(proxyOk) + (proxyOk
                            ? Lang.t("系统代理已清", "system proxy cleared",
                                    "システムプロキシ解除")
                            : Lang.t("系统代理仍在", "system proxy still set",
                                    "システムプロキシ残存"))
                    // A running VPN publishes its own proxy on the tun network; the cleanup above
                    // only touches the global keys, so say which one the witness card sees.
                    + (proxyOk && lastResult != null && lastResult.vpn != null
                            && !lastResult.vpn.isEmpty()
                            ? Lang.t("（VPN 自带代理保留）", " (VPN-declared proxy kept)",
                                    "（VPN 側プロキシは残る）")
                            : "")
                    // Always-on VPN has no shell-reachable API: say so instead of pretending.
                    + "\n· " + Lang.t("常开 VPN 请在「VPN 设置」里手动开启",
                            "always-on VPN: switch it on in VPN settings",
                            "常時 VPN は VPN 設定で手動で ON に");
            int color = v6Ok && proxyOk ? GREEN : AMBER;
            ScreenUpdates.post(screen -> {
                screen.network.showFix(text, color);
                screen.network.netProbe();   // re-check so the table reflects the new state
            });
        }).start();
    }

    private static String tick(boolean ok) { return ok ? "✓ " : "✗ "; }

    private void showFix(String text, int color) {
        if (fixLine == null) return;
        fixLine.setText(text);
        fixLine.setTextColor(color);
        fixLine.setVisibility(View.VISIBLE);
    }

    /** First {@code key=value} token in the FIX line, or null. */
    private static String token(String out, String key) {
        for (String line : out.split("\\n")) {
            int i = line.indexOf(key);
            if (i < 0) continue;
            String rest = line.substring(i + key.length());
            int end = rest.indexOf(' ');
            return end < 0 ? rest.trim() : rest.substring(0, end);
        }
        return null;
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }
}
