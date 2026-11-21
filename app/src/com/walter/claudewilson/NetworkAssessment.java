package com.walter.claudewilson;

import android.content.SharedPreferences;

import com.walter.claudewilson.model.NetworkRules;
import com.walter.claudewilson.model.SimPolicy;
import com.walter.claudewilson.model.Tables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Interprets Bridge + connectivity/iptables probe output into structured verdicts; zero network I/O. */
final class NetworkAssessment {

    static final class Result {
        final String text, vpn;
        /** Package configured as always-on in VPN settings, or empty when off. */
        final String alwaysOn;
        final String egIp, egCc, egCity, egTz, egOrg;
        /** 1 = matches the selected region, 0 = differs, -1 = nothing to compare. */
        final int egState;
        final boolean egDatacenter;
        /** True when egCc appears in catalog/restricted-regions.txt for this target. */
        final boolean egRestricted;
        /** True when kernel transparent proxy (iptables REDIRECT/TPROXY/DNS-53) is active without VpnService. */
        final boolean tproxyDetected;
        /** True when global Settings.Global.http_proxy is set and may leak proxy metadata. */
        final boolean globalProxySet;
        /** True when custom Private DNS (mode=hostname) is bypassing tunnel DNS interception. */
        final boolean privateDnsBypass;
        final String privateDnsSpec;
        /** Non-empty when egress IP flipped compared to previous scan ("old -> new"). */
        final String ipFlip;
        /** -1 unread/neutral, 0 leaked/bad, 1 clean/aligned. */
        final int dnsState, v6State, udpState, routeState;
        final String dnsLabel, dnsDetail;
        final String v6Label, v6Detail;
        final String udpLabel, udpDetail;
        final String routeLabel, routeDetail;

        Result(String text, String vpn, String alwaysOn,
               String egIp, String egCc, String egCity, String egTz, String egOrg,
               int egState, boolean egDatacenter, boolean egRestricted,
               boolean tproxyDetected, boolean globalProxySet,
               boolean privateDnsBypass, String privateDnsSpec, String ipFlip,
               int dnsState, String dnsLabel, String dnsDetail,
               int v6State, String v6Label, String v6Detail,
               int udpState, String udpLabel, String udpDetail,
               int routeState, String routeLabel, String routeDetail) {
            this.text = text;
            this.vpn = vpn;
            this.alwaysOn = alwaysOn;
            this.egIp = egIp;
            this.egCc = egCc;
            this.egCity = egCity;
            this.egTz = egTz;
            this.egOrg = egOrg;
            this.egState = egState;
            this.egDatacenter = egDatacenter;
            this.egRestricted = egRestricted;
            this.tproxyDetected = tproxyDetected;
            this.globalProxySet = globalProxySet;
            this.privateDnsBypass = privateDnsBypass;
            this.privateDnsSpec = privateDnsSpec;
            this.ipFlip = ipFlip;
            this.dnsState = dnsState;
            this.dnsLabel = dnsLabel;
            this.dnsDetail = dnsDetail;
            this.v6State = v6State;
            this.v6Label = v6Label;
            this.v6Detail = v6Detail;
            this.udpState = udpState;
            this.udpLabel = udpLabel;
            this.udpDetail = udpDetail;
            this.routeState = routeState;
            this.routeLabel = routeLabel;
            this.routeDetail = routeDetail;
        }
    }

    private NetworkAssessment() {}

    static Result evaluate(String raw, SharedPreferences p, String exp, String targetName) {
        return evaluate(raw, p, exp, targetName, Tables.EMPTY, "");
    }

    static Result evaluate(String raw, SharedPreferences p, String exp,
                           String targetName, Tables tables, String targetId) {
        Map<String, String> eg = kv(raw, "EGRESS");
        Map<String, String> v6 = kv(raw, "V6");
        Map<String, String> ud = kv(raw, "UDP");
        Map<String, String> lk = kv(raw, "LOCK");
        Map<String, String> rt = kv(raw, "ROUTE");
        StringBuilder s = new StringBuilder();

        String egIp = nn(eg.get("ip"));
        String egCc = nn(eg.get("cc")).toUpperCase(Locale.ROOT);
        String egCity = nn(eg.get("city")).replace('_', ' ').trim();
        String egTz = nn(eg.get("tz")).trim();
        String egOrg = nn(eg.get("org")).trim();
        boolean hostingFlag = "1".equals(nn(eg.get("hosting")));
        boolean proxyFlag = "1".equals(nn(eg.get("proxy")));
        boolean hasCc = !egCc.isEmpty();
        boolean hasRegion = exp != null && !exp.isEmpty();
        boolean matches = hasCc && hasRegion && egCc.equalsIgnoreCase(exp);
        boolean egRestricted = hasCc && SimPolicy.isRestrictedIso(tables, egCc, targetId);
        boolean dcAsn = NetworkRules.isDatacenterOrg(egOrg, proxyFlag, hostingFlag);

        String ipFlip = "";
        if (!egIp.isEmpty()) {
            String hist = p.getString("net_iphist", "");
            String last = hist.isEmpty() ? "" : hist.split(",")[0];
            if (!last.isEmpty() && !last.equals(egIp)) {
                ipFlip = last + " → " + egIp;
                s.append("⚠ ").append(Lang.t("IP 翻转 ", "IP flip ", "IP変化 "))
                        .append(ipFlip).append('\n');
            }
            List<String> hl = new ArrayList<String>(
                    hist.isEmpty() ? new ArrayList<String>() : Arrays.asList(hist.split(",")));
            hl.remove(egIp);
            hl.add(0, egIp);
            if (hl.size() > 8) hl = hl.subList(0, 8);
            p.edit().putString("net_iphist", android.text.TextUtils.join(",", hl)).apply();
        }

        String lapp = cleanNull(lk.get("app"));
        String lastVpn = cleanNull(lk.get("vpn"));
        String pdnsMode = cleanNull(lk.get("pdns"));
        String pdnsSpec = cleanNull(lk.get("pdnsspec"));
        boolean privateDnsBypass = NetworkRules.isPrivateDnsBypassRisk(pdnsMode, pdnsSpec);

        int dns53Count = parseIntSafe(lk.get("dns53"));
        int tptcpCount = parseIntSafe(lk.get("tptcp"));
        boolean tproxyDns = dns53Count > 0;
        boolean tproxyDetected = lastVpn.isEmpty() && (dns53Count > 0 || tptcpCount > 0);

        String gproxy = cleanNull(lk.get("gproxy"));
        boolean globalProxySet = !gproxy.isEmpty() && !":0".equals(gproxy);

        // 1. DNS evaluation (Private DNS bypass > Fake-IP > TProxy DNS-53 > VPN redir-host > Direct leak)
        Map<String, String> df = kv(raw, "DNSFAKE");
        int dnsState = -1;
        String dnsLabel = "—";
        String dnsDetail = Lang.t("未获取到 DNS 解析样本", "no DNS sample", "DNS 観測なし");
        if (privateDnsBypass) {
            dnsState = 0;
            boolean cnDot = NetworkRules.isDomesticPrivateDns(pdnsSpec);
            dnsLabel = cnDot ? Lang.t("国产 DoT", "CN DoT", "国内 DoT")
                    : Lang.t("私有DNS绕过", "DoT bypass", "DoT 迂回");
            dnsDetail = "private_dns=" + pdnsSpec
                    + Lang.t(" · 绕过隧道 DNS 拦截", " · bypasses tunnel DNS");
        } else if (!df.isEmpty()) {
            int total = 0, fake = 0, failed = 0;
            for (Map.Entry<String, String> e : df.entrySet()) {
                if (!e.getKey().contains(".")) continue;
                String value = e.getValue();
                if (value == null || value.isEmpty() || value.startsWith("err")) {
                    failed++;
                    continue;
                }
                total++;
                boolean allFake = true;
                for (String addr : value.split("\\+")) {
                    if (addr.isEmpty()) continue;
                    if (!NetworkRules.isFakeIp(addr)) {
                        allFake = false;
                        break;
                    }
                }
                if (allFake) fake++;
            }
            if (total == 0) {
                dnsState = -1;
                dnsLabel = "—";
                dnsDetail = "dns err=" + failed;
            } else if (fake == total) {
                dnsState = 1;
                dnsLabel = "Fake-IP";
                dnsDetail = fake + "/" + total + " (198.18.x / fc00::)"
                        + (failed > 0 ? " err=" + failed : "");
            } else if (tproxyDns) {
                dnsState = 1;
                dnsLabel = Lang.t("透明代理", "TProxy", "透過");
                dnsDetail = "iptables 53 · redir-host (dns53=" + dns53Count + ")";
            } else if (!lastVpn.isEmpty() && !egRestricted) {
                dnsState = 1;
                dnsLabel = Lang.t("隧道解析", "Tunnel DNS", "トンネル");
                dnsDetail = "redir-host · " + Lang.t("无私有 DNS 绕过", "no Private DNS bypass")
                        + " (" + total + "/" + total + ")";
            } else {
                dnsState = 0;
                dnsLabel = Lang.t("直连解析", "Direct DNS", "直接解決");
                dnsDetail = Lang.t("未检测到隧道 DNS 接管（Fake-IP ", "unproxied DNS (fake-ip ")
                        + fake + "/" + total + ")";
            }
        }

        // 2. IPv6 egress evaluation (local leak + v4/v6 cross-country mismatch)
        String v6ip = nn(v6.get("ip"));
        String v6cc = nn(v6.get("cc")).toUpperCase(Locale.ROOT);
        StringBuilder localV6Blob = new StringBuilder();
        for (Map.Entry<String, String> e : v6.entrySet()) {
            if (!"ip".equals(e.getKey()) && !"cc".equals(e.getKey()) && e.getValue() != null) {
                localV6Blob.append(e.getValue()).append(' ');
            }
        }
        boolean v6Restricted = !v6cc.isEmpty() && SimPolicy.isRestrictedIso(tables, v6cc, targetId);
        int v6Class = NetworkRules.classifyV6(v6ip, v6cc, localV6Blob.toString(), egCc, v6Restricted);
        int v6State;
        String v6Label;
        String v6Detail;
        if (v6Class == NetworkRules.V6_CLEAN_NONE) {
            v6State = 1;
            v6Label = Lang.t("无泄露", "Clean", "漏洩なし");
            v6Detail = Lang.t("无真实 v6 出口（隧道截断或本地无 v6）",
                    "no v6 egress (tunnel-dropped)", "v6 出口なし");
        } else if (v6Class == NetworkRules.V6_LEAK_LOCAL) {
            v6State = 0;
            v6Label = Lang.t("本机泄露", "Leaked", "実址漏洩");
            v6Detail = v6ip + Lang.t(" ← 本机真实地址", " ← real device addr", " ← 実アドレス");
        } else if (v6Class == NetworkRules.V6_LEAK_CROSS_COUNTRY) {
            v6State = 0;
            v6Label = v6Restricted ? Lang.t("禁区 v6", "Banned v6") : Lang.t("跨国错位", "v6 Mismatch");
            v6Detail = "v6 " + (v6cc.isEmpty() ? "?" : v6cc)
                    + " ≠ v4 " + (egCc.isEmpty() ? "?" : egCc) + " · " + v6ip;
        } else {
            v6State = 1;
            v6Label = v6cc.isEmpty() ? Lang.t("无泄露", "Clean", "漏洩なし")
                    : Lang.t("双栈同国", "Aligned", "同国 v6");
            v6Detail = Lang.t("隧道 v6 ", "tunnel v6 ")
                    + (v6cc.isEmpty() ? "" : "(" + v6cc + ") ") + v6ip;
        }

        // 3. UDP / STUN evaluation
        String uip = ud.get("ip");
        String ucc = nn(ud.get("cc")).toUpperCase(Locale.ROOT);
        String uvia = nn(ud.get("via"));
        int udpState;
        String udpLabel;
        String udpDetail;
        if (uip == null || uip.isEmpty()) {
            udpState = -1;
            udpLabel = "—";
            udpDetail = "STUN unread";
        } else if ("timeout".equals(uip) || "err".equals(uip)) {
            udpState = 1;
            udpLabel = Lang.t("已阻断", "Blocked", "遮断");
            udpDetail = "STUN " + uip + Lang.t(" · 无 UDP 泄露面", " · no UDP leak surface", " · 漏洩なし");
        } else if (uip.equals(egIp)) {
            udpState = 1;
            udpLabel = Lang.t("同出口", "Aligned", "同出口");
            udpDetail = "STUN " + uip + (ucc.isEmpty() ? "" : " (" + ucc + ")");
        } else {
            boolean same = !ucc.isEmpty() && !egCc.isEmpty() && ucc.equalsIgnoreCase(egCc);
            udpState = same ? 1 : 0;
            udpLabel = same ? Lang.t("同国出口", "Same CC", "同国")
                    : Lang.t("跨国泄露", "Leaked", "実出口");
            udpDetail = "STUN " + uip + (ucc.isEmpty() ? "" : " (" + ucc + ")")
                    + (uvia.isEmpty() ? "" : " [" + uvia + "]");
        }

        // 4. Target App Route Verification (real VPN UidRanges / iptables TProxy UID check)
        int targetUid = parseIntSafe(rt.get("uid"));
        int uidBypass = parseIntSafe(rt.get("uidbypass"));
        String uidRanges = cleanNull(rt.get("uidranges"));
        int routeState;
        String routeLabel;
        String routeDetail;
        if (!lastVpn.isEmpty()) {
            if (targetUid > 0 && !uidRanges.isEmpty()) {
                boolean covered = NetworkRules.uidInRanges(targetUid, uidRanges);
                routeState = covered ? 1 : 0;
                routeLabel = covered ? Lang.t("已入隧道", "In VPN", "VPN 内")
                        : Lang.t("绕过 VPN", "Bypassed", "VPN 迂回");
                routeDetail = targetName + " (uid=" + targetUid + ") "
                        + (covered ? Lang.t("已纳入 VPN 路由", "routed via ") + " · " + lastVpn
                        : Lang.t("被 VPN 分应用排除！", "excluded from VPN UidRanges!"));
            } else {
                routeState = 1;
                routeLabel = Lang.t("VPN 接管", "VPN On", "VPN 有効");
                routeDetail = targetName + " · " + lastVpn
                        + (targetUid > 0 ? " (uid=" + targetUid + ")" : "");
            }
        } else if (tproxyDetected) {
            if (uidBypass > 0) {
                routeState = 0;
                routeLabel = Lang.t("绕过代理", "Bypassed", "迂回");
                routeDetail = targetName + " (uid=" + targetUid + ") "
                        + Lang.t("命中 iptables RETURN 放行规则！", "excluded by iptables owner RETURN!");
            } else {
                routeState = 1;
                routeLabel = Lang.t("透明代理", "TProxy", "透過代理");
                routeDetail = targetName + (targetUid > 0 ? " (uid=" + targetUid + ")" : "")
                        + " · REDIRECT/TProxy (tcp=" + tptcpCount + ", dns53=" + dns53Count + ")";
            }
        } else {
            boolean directRisk = egRestricted || (hasCc && hasRegion && !matches);
            routeState = directRisk ? 0 : -1;
            routeLabel = directRisk ? Lang.t("直连裸奔", "Direct", "直通")
                    : Lang.t("无隧道", "No tunnel", "トンネル無");
            routeDetail = targetName + Lang.t(" 未检测到 VPN 或内核透明代理",
                    " no VpnService or kernel TProxy detected");
        }

        return new Result(
                s.toString(), lastVpn, lapp,
                egIp, egCc, egCity, egTz, egOrg,
                !hasCc || !hasRegion ? -1 : matches ? 1 : 0,
                dcAsn, egRestricted, tproxyDetected, globalProxySet,
                privateDnsBypass, pdnsSpec, ipFlip,
                dnsState, dnsLabel, dnsDetail,
                v6State, v6Label, v6Detail,
                udpState, udpLabel, udpDetail,
                routeState, routeLabel, routeDetail);
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    private static String cleanNull(String v) {
        if (v == null) return "";
        String t = v.trim();
        return "null".equalsIgnoreCase(t) ? "" : t;
    }

    private static int parseIntSafe(String v) {
        if (v == null || v.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Parses "KEY k=v k=v" line -> map (keeps spaces inside the last k=v value). */
    private static Map<String, String> kv(String raw, String key) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (raw == null) return m;
        for (String line : raw.split("\n")) {
            if (!line.startsWith(key + " ")) continue;
            for (String tok : line.substring(key.length() + 1).split(" ")) {
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    m.put(tok.substring(0, eq), tok.substring(eq + 1));
                } else if (!m.isEmpty()) {
                    String lastK = null;
                    for (String k : m.keySet()) lastK = k;
                    if (lastK != null) m.put(lastK, m.get(lastK) + " " + tok);
                }
            }
        }
        return m;
    }
}
