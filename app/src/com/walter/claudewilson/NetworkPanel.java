package com.walter.claudewilson;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.walter.claudewilson.platform.TablesLoader;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Redesigned Network ("网络") card:
 * <ol>
 *   <li><b>Egress Hero Block</b>: country + IP + ASN + residential/datacenter/restricted badge +
 *       pure read-only Scan button (plus inline IP-flip alert when exit IP changed).</li>
 *   <li><b>2x2 Leak-Guard Matrix</b>: DNS / IPv6 / UDP / Rule Hit tiles; tapping any tile expands
 *       its raw probe detail inline.</li>
 *   <li><b>Tunnel Action Strip</b>: live VpnService / TProxy status + conditional Clear Proxy
 *       button (shown only when global {@code http_proxy} is actually set) + VPN Settings.</li>
 * </ol>
 */
final class NetworkPanel extends UiSection {
    private final Supplier<String> expectedRegion;

    private LinearLayout cardHost;

    private static volatile NetworkAssessment.Result lastResult;
    private static volatile long lastProbeAt;
    private static volatile boolean netBusy;
    private static volatile String expandedGuard;
    private static volatile String lastError = "";
    private long lastNicScanAt;
    private String lastNicLabel = "";

    /** Live local VPN detection; updated via ConnectivityManager callback and shell fallback. */
    private String vpnNowPkg = "";
    private String vpnNowLabel = "";
    private boolean vpnFromShell;
    private volatile boolean vpnShellBusy;
    private long vpnShellAt;
    private String alwaysOnNowPkg = "";
    private boolean alwaysOnNowReadable;
    private boolean vpnWatching;
    private android.net.ConnectivityManager.NetworkCallback vpnCallback;

    static NetworkAssessment.Result lastResult() {
        return lastResult;
    }

    NetworkPanel(Context context, LinearLayout root, Supplier<String> expectedRegion) {
        super(context);
        this.root = root;
        this.expectedRegion = expectedRegion;
    }

    void render() {
        readVpnNow();
        watchVpn();
        if (vpnNowLabel.isEmpty()) {
            probeVpnShell();
        }

        section(Lang.t("网络", "Network", "ネットワーク"));

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
    }

    private void repaintCard() {
        if (cardHost == null) {
            return;
        }
        cardHost.removeAllViews();
        readVpnNow();
        final NetworkAssessment.Result r = lastResult;
        if (vpnNowLabel.isEmpty() && r != null && r.vpn != null && !r.vpn.isEmpty()) {
            vpnNowPkg = r.vpn;
            vpnNowLabel = appLabel(r.vpn);
        }

        boolean tproxy = vpnNowLabel.isEmpty() && r != null && r.tproxyDetected;

        // =====================================================================
        // LAYER 1: EGRESS HERO BLOCK
        // =====================================================================
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);

        String expRegion = expectedRegion.get();
        boolean hasExp = expRegion != null && !expRegion.isEmpty();
        boolean scanned = r != null && (!r.egIp.isEmpty() || !r.egCc.isEmpty());

        int heroColor;
        String heroTitle;
        if (!scanned) {
            heroColor = DIM;
            heroTitle = netBusy ? Lang.t("探测中…", "Probing…", "計測中…")
                    : Lang.t("未探测", "Not scanned", "未計測");
        } else {
            boolean ccMatch = !hasExp || r.egCc.equalsIgnoreCase(expRegion);
            heroColor = r.egRestricted ? RED : !ccMatch ? AMBER : GREEN;
            String maskedIp = Privacy.mask(r.egIp.isEmpty() ? "?" : r.egIp);
            heroTitle = (r.egCc.isEmpty() ? "?" : r.egCc) + " · " + maskedIp;
        }

        TextView heroTv = txt(heroTitle, 14, heroColor);
        heroTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        heroTv.setSingleLine(true);
        heroTv.setEllipsize(TextUtils.TruncateAt.END);
        heroTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        heroTop.addView(heroTv);

        if (scanned) {
            if (r.egRestricted) {
                TextView banBdg = pillText(Lang.t("禁区出口", "Banned", "制限国"),
                        9, RED, 0x22E55353, RED);
                banBdg.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.leftMargin = dp(this, 6);
                banBdg.setLayoutParams(blp);
                heroTop.addView(banBdg);
            } else if (!r.egOrg.isEmpty()) {
                int lc = r.egDatacenter ? AMBER : GREEN;
                String lt = r.egDatacenter ? Lang.t("机房 IP", "DC", "DC")
                        : Lang.t("住宅 IP", "Resi", "住宅");
                TextView lineBdg = pillText(lt, 9, lc,
                        r.egDatacenter ? 0x22E5A93B : 0x2234D399, lc);
                lineBdg.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.leftMargin = dp(this, 6);
                lineBdg.setLayoutParams(blp);
                heroTop.addView(lineBdg);
            }
        }

        TextView scanBtn = netBusy
                ? pillText(Lang.t("检测中", "Scan…", "検査中"), 10, CYAN, CARD2, CYAN)
                : primaryChip(Lang.t("检测", "Scan", "検査"), CYAN, 0xFF041918);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(this, 7);
        scanBtn.setLayoutParams(slp);
        bindPress(scanBtn);
        scanBtn.setOnClickListener(v -> startProbe(true));
        heroTop.addView(scanBtn);
        cardHost.addView(heroTop);

        // ASN Org + City + Timezone + Expected Country Diff Sub-row
        if (scanned && (!r.egOrg.isEmpty() || !r.egCity.isEmpty() || !r.egTz.isEmpty()
                || (hasExp && !r.egCc.equalsIgnoreCase(expRegion)))) {
            LinearLayout sub = new LinearLayout(this);
            sub.setOrientation(LinearLayout.HORIZONTAL);
            sub.setGravity(Gravity.CENTER_VERTICAL);
            sub.setPadding(0, dp(this, 4), 0, 0);

            StringBuilder meta = new StringBuilder();
            if (!r.egOrg.isEmpty()) meta.append(r.egOrg);
            if (!r.egCity.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(r.egCity);
            }
            if (!r.egTz.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(r.egTz);
            }
            TextView orgTv = txt(meta.length() == 0 ? "—" : meta.toString(), 9.5f, DIM);
            orgTv.setTypeface(Typeface.MONOSPACE);
            orgTv.setSingleLine(true);
            orgTv.setEllipsize(TextUtils.TruncateAt.END);
            orgTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            sub.addView(orgTv);

            if (hasExp && !r.egCc.isEmpty() && !r.egCc.equalsIgnoreCase(expRegion)) {
                TextView diffTv = txt(Lang.t("期望 ", "exp ")
                        + expRegion.toUpperCase(Locale.ROOT), 9.5f, RED);
                diffTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                dlp.leftMargin = dp(this, 8);
                diffTv.setLayoutParams(dlp);
                sub.addView(diffTv);
            }
            cardHost.addView(sub);
        }

        if (!lastError.isEmpty()) {
            TextView errTv = txt(lastError, 9.5f, RED);
            errTv.setTypeface(Typeface.MONOSPACE);
            errTv.setPadding(0, dp(this, 4), 0, 0);
            cardHost.addView(errTv);
        }

        // =====================================================================
        // LAYER 2: 2x2 LEAK-GUARD MATRIX (DNS / IPv6 / UDP / Rule)
        // =====================================================================
        String dnsVal = r == null ? "—" : r.dnsLabel;
        int dnsColor = stateColor(r == null ? -1 : r.dnsState, false);
        String dnsDet = r == null
                ? Lang.t("点击「检测」执行 shell 侧只读探测", "Tap Scan to probe via shell")
                : r.dnsDetail;

        final boolean ipv6SpoofOn = new PersonaState(this).ipv6SpoofEnabled();
        String v6Val;
        int v6Color;
        String v6Det;
        if (!ipv6SpoofOn && (r == null || r.v6State == 1)) {
            v6Val = Lang.t("已关闭", "Off", "無効");
            v6Color = GREEN;
            v6Det = Lang.t("全网卡 IPv6 已关闭 · 切网/飞行模式自动保持",
                    "All-iface IPv6 off · persists across net/airplane",
                    "全IF IPv6 無効 · 切替/機内モード維持");
        } else {
            v6Val = r == null ? "—" : r.v6Label;
            v6Color = stateColor(r == null ? -1 : r.v6State, false);
            v6Det = r == null
                    ? Lang.t("检测真实 IPv6 出口是否泄露本机地址", "Checks real IPv6 egress leak")
                    : r.v6Detail;
        }

        String udpVal = r == null ? "—" : r.udpLabel;
        int udpColor = stateColor(r == null ? -1 : r.udpState, false);
        String udpDet = r == null
                ? Lang.t("STUN 探测 UDP 是否与 TCP 同出口", "Checks STUN UDP egress alignment")
                : r.udpDetail;

        String routeVal = r == null ? "—" : r.routeLabel;
        int routeColor = r == null ? MUTE : stateColor(r.routeState, false);
        String routeDet = r == null
                ? Lang.t("检测目标应用 UID 是否真正纳入 VPN / 透明代理",
                        "Checks whether target app UID is routed inside VPN/TProxy")
                : r.routeDetail;

        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rAlp = new LinearLayout.LayoutParams(-1, -2);
        rAlp.topMargin = dp(this, 11);
        rAlp.bottomMargin = dp(this, 6);
        rowA.setLayoutParams(rAlp);
        rowA.addView(guardTile("dns", "DNS", dnsVal, dnsColor, 0, dp(this, 6)));
        rowA.addView(guardTile("v6", "IPv6", v6Val, v6Color, 0, 0));
        cardHost.addView(rowA);

        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rBlp = new LinearLayout.LayoutParams(-1, -2);
        rBlp.bottomMargin = dp(this, 4);
        rowB.setLayoutParams(rBlp);
        rowB.addView(guardTile("udp", "UDP", udpVal, udpColor, 0, dp(this, 6)));
        rowB.addView(guardTile("route", Lang.t("目标路由", "Target Route", "対象経路"),
                routeVal, routeColor, 0, 0));
        cardHost.addView(rowB);

        // Inline Expandable Detail Strip (only when user taps one of the 4 tiles)
        if (expandedGuard != null) {
            String detTitle = "dns".equals(expandedGuard) ? "DNS"
                    : "v6".equals(expandedGuard) ? "IPv6"
                    : "udp".equals(expandedGuard) ? "UDP"
                    : Lang.t("目标路由", "Target Route", "対象経路");
            String detBody = "dns".equals(expandedGuard) ? dnsDet
                    : "v6".equals(expandedGuard) ? v6Det
                    : "udp".equals(expandedGuard) ? udpDet
                    : routeDet;

            LinearLayout det = new LinearLayout(this);
            det.setOrientation(LinearLayout.HORIZONTAL);
            det.setGravity(Gravity.CENTER_VERTICAL);
            det.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
            det.setBackground(roundBox(CARD2, STROKE, 11));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = dp(this, 4);
            dlp.bottomMargin = dp(this, 4);
            det.setLayoutParams(dlp);

            TextView dt = txt(detTitle + " · " + detBody, 9.5f, FG);
            dt.setTypeface(Typeface.MONOSPACE);
            dt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            det.addView(dt);

            if ("v6".equals(expandedGuard)) {
                TextView v6Toggle = txt(
                        ipv6SpoofOn
                                ? Lang.t("关闭 IPv6", "Disable", "無効化")
                                : Lang.t("伪装开关", "Mode", "切替"),
                        9.5f, ipv6SpoofOn ? GREEN : CYAN);
                v6Toggle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                v6Toggle.setSingleLine(true);
                v6Toggle.setPadding(dp(this, 8), dp(this, 3), dp(this, 8), dp(this, 3));
                v6Toggle.setBackground(roundBox(
                        ipv6SpoofOn ? 0x1F34D399 : 0x1F2EC4B6,
                        ipv6SpoofOn ? 0x6634D399 : 0x662EC4B6, 10));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
                tlp.leftMargin = dp(this, 6);
                v6Toggle.setLayoutParams(tlp);
                bindPress(v6Toggle);
                v6Toggle.setOnClickListener(v -> PersonaEditSheets.showIpv6ModeSheet(
                        this, ipv6SpoofOn, expectedRegion != null ? expectedRegion.get() : "",
                        new PersonaState(this).ipv6Prefix(Dashboard.activeUserId()), on -> {
                            boolean enable = Boolean.TRUE.equals(on);
                            if (getBaseContext() instanceof Dashboard) {
                                ((Dashboard) getBaseContext()).setIpv6SpoofEnabled(enable);
                            } else {
                                new PersonaState(this).putIpv6SpoofEnabled(enable);
                                new Thread(() -> new RootActions(this)
                                        .syncIpv6Enforcement(enable), "ipv6-net-toggle").start();
                                repaintCard();
                            }
                        }));
                det.addView(v6Toggle);
            }

            TextView close = txt("✕", 10, MUTE);
            close.setPadding(dp(this, 8), dp(this, 2), dp(this, 2), dp(this, 2));
            det.addView(close);
            det.setOnClickListener(v -> {
                expandedGuard = null;
                repaintCard();
            });
            cardHost.addView(det);
        }

        // =====================================================================
        // LAYER 3: CLEAN TUNNEL FOOTER BAR
        // =====================================================================
        View div = new View(this);
        div.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(this, 1));
        divLp.topMargin = dp(this, 8);
        divLp.bottomMargin = dp(this, 9);
        div.setLayoutParams(divLp);
        cardHost.addView(div);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        String footText = "";
        int footColor = MUTE;
        if (r != null) {
            if (r.routeState == 0) {
                footText = Lang.t("目标绕过隧道", "Target bypassed", "対象が迂回中");
                footColor = RED;
            } else if (r.privateDnsBypass) {
                footText = Lang.t("私有 DNS 绕过", "Private DNS bypass", "DoT 迂回");
                footColor = RED;
            } else if (!tproxy) {
                boolean aoOn = r.alwaysOn != null && !r.alwaysOn.isEmpty();
                if (!aoOn) {
                    footText = Lang.t("常开 VPN 未锁定", "Always-on off", "常時 VPN 未設定");
                    footColor = AMBER;
                }
            }
        }
        TextView footTv = txt(footText, 9.5f, footColor);
        footTv.setTypeface(Typeface.MONOSPACE);
        footTv.setSingleLine(true);
        footTv.setEllipsize(TextUtils.TruncateAt.END);
        footTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(footTv);

        // Conditional Clear Proxy pill: ONLY shown when Settings.Global.http_proxy is actually set!
        if (r != null && r.globalProxySet && RootShell.state() == RootShell.ST_READY) {
            TextView clearPx = pillText(Lang.t("清代理", "Clear proxy", "Proxy解除"),
                    9.5f, RED, CARD2, RED);
            clearPx.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.leftMargin = dp(this, 6);
            clearPx.setLayoutParams(plp);
            bindPress(clearPx);
            clearPx.setOnClickListener(v -> clearGlobalProxy());
            footer.addView(clearPx);
        }

        // Conditional Disable Private DNS pill: ONLY shown when custom DoT bypasses tunnel DNS!
        if (r != null && r.privateDnsBypass && RootShell.state() == RootShell.ST_READY) {
            TextView offDns = pillText(Lang.t("关私有DNS", "Disable DoT", "DoT オフ"),
                    9.5f, RED, CARD2, RED);
            offDns.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
            dlp.leftMargin = dp(this, 6);
            offDns.setLayoutParams(dlp);
            bindPress(offDns);
            offDns.setOnClickListener(v -> disablePrivateDns());
            footer.addView(offDns);
        }

        // Contextual jump to active VPN client when an issue needs node/per-app adjustment
        boolean needsProxyAction = r != null && (r.egRestricted || r.egState == 0
                || r.routeState == 0 || r.v6State == 0 || r.udpState == 0);
        if (needsProxyAction && vpnNowPkg != null && !vpnNowPkg.isEmpty()) {
            final String launchPkg = vpnNowPkg;
            String lbl = vpnNowLabel.isEmpty() ? Lang.t("代理应用", "Proxy") : vpnNowLabel;
            if (lbl.length() > 10) lbl = lbl.substring(0, 10);
            TextView openVpn = pillText(Lang.t("打开 ", "Open ") + lbl,
                    9.5f, SAND, CARD2, 0x557DD3D8);
            openVpn.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.leftMargin = dp(this, 6);
            openVpn.setLayoutParams(olp);
            bindPress(openVpn);
            openVpn.setOnClickListener(v -> openProxyApp(launchPkg));
            footer.addView(openVpn);
        }

        TextView vpnBtn = pillText(Lang.t("VPN 设置", "VPN settings", "VPN 設定"),
                9.5f, FG, CARD2, STROKE);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = dp(this, 6);
        vpnBtn.setLayoutParams(vlp);
        bindPress(vpnBtn);
        vpnBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            } catch (Throwable t) {
                Toast.makeText(this, "VPN settings unavailable", Toast.LENGTH_SHORT).show();
            }
        });
        footer.addView(vpnBtn);

        cardHost.addView(footer);
    }

    private View guardTile(final String id, String title, String value, int valColor,
                           int leftM, int rightM) {
        boolean active = id.equals(expandedGuard);
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(this, 11), dp(this, 9), dp(this, 11), dp(this, 9));
        tile.setBackground(roundBox(CARD2, active ? CYAN : STROKE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftM;
        lp.rightMargin = rightM;
        tile.setLayoutParams(lp);

        TextView t = txt(title, 9.5f, DIM);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(t);

        TextView v = txt(value, 11, valColor);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, dp(this, 3), 0, 0);
        tile.addView(v);

        bindPress(tile);
        tile.setOnClickListener(view -> {
            expandedGuard = active ? null : id;
            repaintCard();
        });
        return tile;
    }

    // =========================================================================
    // PROBE & CLEAR GLOBAL PROXY ACTIONS
    // =========================================================================

    private void startProbe(boolean showProgress) {
        if (netBusy) {
            return;
        }
        if (RootShell.state() != RootShell.ST_READY) {
            if (showProgress) {
                lastError = Lang.t("需要 root 或 Shizuku", "needs root or Shizuku");
                repaintCard();
            }
            return;
        }
        final String prevAsn = lastEgressAsn();
        netBusy = true;
        lastError = "";
        if (showProgress) {
            repaintCard();
        }
        new Thread(() -> {
            try {
                com.walter.claudewilson.model.TargetSpec target = Targets.current();
                String raw = NetworkActions.probe(getPackageName(), target);
                NetworkAssessment.Result result = NetworkAssessment.evaluate(
                        raw, getSharedPreferences("apply", 0), expectedRegion.get(),
                        target.displayName(), TablesLoader.load(getApplicationContext()),
                        target.id());
                lastResult = result;
                lastProbeAt = android.os.SystemClock.elapsedRealtime();
                final String newAsn = lastEgressAsn();
                final boolean asnChanged = prevAsn == null ? newAsn != null
                        : !prevAsn.equals(newAsn);
                netBusy = false;
                ScreenUpdates.post(screen -> {
                    if (screen.network != null) {
                        screen.network.repaintCard();
                    }
                    screen.refreshPersona();
                    screen.refreshPreflight();
                    if (asnChanged && screen.personaPanel != null
                            && screen.isIpv6SpoofEnabled()
                            && RegionSelection.regionIdx >= 0
                            && Targets.current().hooksEnabled()) {
                        screen.personaPanel.publishRotated();
                    }
                });
            } catch (Throwable t) {
                lastError = "error: " + t.getClass().getSimpleName();
                netBusy = false;
                ScreenUpdates.post(screen -> {
                    if (screen.network != null) {
                        screen.network.repaintCard();
                    }
                });
            }
        }, "net-probe").start();
    }

    /** Auto-scan after region switch. Silent; re-publishes if ASN changed. */
    void autoProbe() {
        startProbe(false);
    }

    boolean hasRecentProbe(long windowMs) {
        return lastResult != null && lastProbeAt > 0L
                && (android.os.SystemClock.elapsedRealtime() - lastProbeAt) < windowMs;
    }

    private void clearGlobalProxy() {
        if (RootShell.state() != RootShell.ST_READY) {
            return;
        }
        final String pkg = vpnNowPkg == null ? "" : vpnNowPkg;
        new Thread(() -> {
            String out = RootShell.runCapture(NetworkActions.fixCommand(pkg));
            String proxy = token(out, "proxy=");
            boolean ok = proxy == null || proxy.isEmpty() || "null".equals(proxy);
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen,
                        ok ? Lang.t("系统全局代理已清", "System proxy cleared")
                                : Lang.t("系统代理仍在", "System proxy still set"),
                        Toast.LENGTH_SHORT).show();
                if (screen.network != null) {
                    screen.network.startProbe(true);
                }
            });
        }, "net-clear-proxy").start();
    }

    private void disablePrivateDns() {
        if (RootShell.state() != RootShell.ST_READY) {
            return;
        }
        new Thread(() -> {
            String out = RootShell.runCapture(NetworkActions.disablePrivateDnsCommand());
            String mode = token(out, "mode=");
            boolean ok = "off".equalsIgnoreCase(mode);
            ScreenUpdates.post(screen -> {
                Toast.makeText(screen,
                        ok ? Lang.t("私有 DNS 已关闭", "Private DNS disabled")
                                : Lang.t("私有 DNS 未变更", "Private DNS unchanged"),
                        Toast.LENGTH_SHORT).show();
                if (screen.network != null) {
                    screen.network.startProbe(true);
                }
            });
        }, "net-off-pdns").start();
    }

    private void openProxyApp(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    /** Extracts the ASN number from the last scan's egOrg (e.g. "AS13335 Cloudflare" -> "13335"). */
    static String lastEgressAsn() {
        NetworkAssessment.Result r = lastResult;
        if (r == null || r.egOrg == null || r.egOrg.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("AS(\\d+)").matcher(r.egOrg);
        return m.find() ? m.group(1) : null;
    }

    /** Returns the last scanned egress country code, or null. */
    static String lastEgressCc() {
        NetworkAssessment.Result r = lastResult;
        return r != null && r.egCc != null && !r.egCc.isEmpty() ? r.egCc : null;
    }

    // =========================================================================
    // LIVE VPN DETECTION
    // =========================================================================

    private void readVpnNow() {
        vpnNowPkg = "";
        vpnNowLabel = "";
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
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastNicScanAt > 0L && (now - lastNicScanAt) < 4000L) {
            vpnNowLabel = lastNicLabel;
            return;
        }
        lastNicScanAt = now;
        lastNicLabel = "";
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("tun") || n.startsWith("ppp")
                        || n.startsWith("wg") || n.startsWith("utun")) {
                    lastNicLabel = n;
                    vpnNowLabel = n;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

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
                if (l.isEmpty() || l.startsWith("SU_RC") || l.startsWith("rc=")
                        || l.startsWith("EXC") || l.startsWith("exc:")
                        || l.startsWith("timeout:")) continue;
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
        repaintCard();
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

    void refreshVpn() {
        readVpnNow();
        if (vpnNowLabel.isEmpty()) probeVpnShell();
        repaintCard();
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
            String app = Settings.Secure.getString(getContentResolver(), "always_on_vpn_app");
            alwaysOnNowReadable = true;
            alwaysOnNowPkg = app == null || "null".equals(app) ? "" : app;
        } catch (Throwable t) {
            alwaysOnNowReadable = false;
            alwaysOnNowPkg = "";
        }
    }

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

    private static int stateColor(int st, boolean warnOnUnread) {
        if (st == 1) return GREEN;
        if (st == 0) return RED;
        return warnOnUnread ? AMBER : MUTE;
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

    private static String token(String out, String key) {
        if (out == null) return null;
        for (String line : out.split("\\n")) {
            int i = line.indexOf(key);
            if (i < 0) continue;
            String rest = line.substring(i + key.length());
            int end = rest.indexOf(' ');
            return end < 0 ? rest.trim() : rest.substring(0, end);
        }
        return null;
    }
}
