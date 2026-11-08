package com.walter.claudewilson;

import android.content.SharedPreferences;
import java.util.Locale;

/** Interpret the existing Bridge text protocol; performs no network I/O. */
final class NetworkAssessment {
    private static final String[] DC_ASN = {
        "amazon", "aws", "google", "microsoft", "azure", "digitalocean",
        "vultr", "linode", "akamai", "cloudflare", "ovh", "hetzner",
        "alibaba", "aliyun", "tencent", "oracle", "datacamp", "m247",
        "choopa", "contabo", "leaseweb", "astrix", "hosting", "datacenter",
        "data center", "server", "cdn", "gsl", "gsl networks"};

    static final class Result {
        final String text, vpn;
        /** Package configured as always-on in the VPN settings, or null when the switch is off. */
        final String alwaysOn;
        /** Egress facts, kept structured so the card can render them as a table. */
        final String egIp, egCc, egOrg;
        /** 1 = matches the selected region, 0 = differs, -1 = nothing to compare. */
        final int egState;
        /** True when the egress organisation looks like a datacenter rather than a home line. */
        final boolean egDatacenter;
        /** -1 unread, 0 leaked, 1 not leaked. */
        final int dnsState, v6State, udpState, tgtState;
        Result(String text, String vpn, String alwaysOn, String egIp, String egCc, String egOrg,
               int egState, boolean egDatacenter,
               int dnsState, int v6State, int udpState, int tgtState) {
            this.text = text; this.vpn = vpn; this.alwaysOn = alwaysOn;
            this.egIp = egIp; this.egCc = egCc; this.egOrg = egOrg;
            this.egState = egState; this.egDatacenter = egDatacenter;
            this.dnsState = dnsState; this.v6State = v6State;
            this.udpState = udpState; this.tgtState = tgtState;
        }
    }

    private NetworkAssessment() {}

    static Result evaluate(String raw, SharedPreferences p, String exp, String targetName) {
        java.util.Map<String, String> eg = kv(raw, "EGRESS");
        java.util.Map<String, String> v6 = kv(raw, "V6");
        java.util.Map<String, String> ud = kv(raw, "UDP");
        java.util.Map<String, String> lk = kv(raw, "LOCK");
        StringBuilder s = new StringBuilder();
        String egIp = eg.get("ip"), egCc = eg.get("cc"),
               egOrg = eg.get("org");
        boolean dcAsn = false;
        // With no region selected there is nothing to compare the egress country against. That is
        // "not compared", not a match: the old expression treated exp == null as agreement and
        // printed a green tick for a comparison that never happened.
        boolean hasCc = egCc != null && !egCc.isEmpty();
        boolean hasRegion = exp != null && !exp.isEmpty();
        boolean matches = hasCc && hasRegion && egCc.equalsIgnoreCase(exp);
        s.append(!hasCc || !hasRegion ? "— " : matches ? "✓ " : "✗ ")
         .append(Lang.t("出口", "egress", "出口")).append(' ')
         .append(egIp == null || egIp.isEmpty() ? "?" : egIp).append(' ')
         .append(hasCc ? egCc : "?")
         .append(hasRegion ? " vs " + exp
                 : Lang.t(" (未选区·不比对)", " (no region selected)",
                         " (地域未選択)"))
         .append('\n');
        if (egOrg != null && !egOrg.isEmpty()) {
            // The organisation is already a column in the table; keep the datacenter verdict for
            // Result.egDatacenter, but do not repeat the name in the prose below it.
            String ol = egOrg.toLowerCase(Locale.ROOT);
            for (String k : DC_ASN) if (ol.contains(k)) { dcAsn = true; break; }
        }        if (egIp != null && !egIp.isEmpty()) {
            String hist = p.getString("net_iphist", "");
            String last = hist.isEmpty() ? "" : hist.split(",")[0];
            if (!last.isEmpty() && !last.equals(egIp))
                s.append("⚠ ").append(Lang.t("IP 翻转 ", "IP flip ", "IP変化 "))
                 .append(last).append("→").append(egIp).append('\n');
            java.util.List<String> hl = new java.util.ArrayList<>(
                    hist.isEmpty() ? new java.util.ArrayList<String>()
                            : java.util.Arrays.asList(hist.split(",")));
            hl.remove(egIp); hl.add(0, egIp);
            if (hl.size() > 8) hl = hl.subList(0, 8);
            p.edit().putString("net_iphist",
                    android.text.TextUtils.join(",", hl)).apply();
        }
        java.util.Map<String, String> df = kv(raw, "DNSFAKE");
        int dnsState = -1;
        if (!df.isEmpty()) {
            int total = 0, fake = 0, failed = 0;
            for (java.util.Map.Entry<String, String> e : df.entrySet()) {
                if (!e.getKey().contains(".")) continue;
                String value = e.getValue();
                // `err` is a failed lookup, not a real-IP answer. Counting it as leaked was a
                // false positive: one transient resolver failure made the whole row red.
                if (value == null || value.isEmpty() || value.startsWith("err")) {
                    failed++;
                    continue;
                }
                total++;
                if (value.contains("198.18.") || value.contains("198.19.")) fake++;
            }
            boolean real = total > 0 && fake < total;
            dnsState = total == 0 ? -1 : real ? 0 : failed > 0 ? -1 : 1;
            s.append(total == 0 ? "— dns·"
                    : real ? "✗ dns·" : failed > 0 ? "— dns·" : "✓ dns·")
             .append(Lang.t("国外路径 ", "foreign ", "国外 "))
             .append(fake).append('/').append(total)
             .append(failed > 0 ? " err=" + failed : "")
             .append(real
                     ? Lang.t(" ←有域返回真实 IP=fake-ip 未覆盖,该域或直连",
                             " ←real IP returned=fake-ip uncovered or "
                             + "direct-routed", " ←実IP返却")
                     : failed > 0
                             ? Lang.t(" ←部分域解析失败,不能判泄漏",
                                     " ←some lookups failed; not a verdict",
                                     " ←一部解決失敗")
                             : Lang.t(" 全 fake-ip=国外域零外部解析(节点侧解析)",
                                     " all fake-ip=zero external lookup "
                                     + "(resolved node-side)",
                                     " 全 fake-ip=外部解無し"))
             .append('\n');
        }
        String v6ip = v6.get("ip");
        int v6State;
        if ("none".equals(v6ip) || v6ip == null || v6ip.isEmpty()) {
            v6State = 1;
            s.append("✓ v6 ").append(Lang.t("无出口(隧道截断或本地无 v6)",
                    "no egress (tunnel-dropped or no v6)",
                    "出口無し")).append('\n');
        } else {
            boolean isLocal = false;
            for (java.util.Map.Entry<String, String> e : v6.entrySet())
                if (!"ip".equals(e.getKey()) && e.getValue() != null
                        && e.getValue().contains(v6ip)) isLocal = true;
            v6State = isLocal ? 0 : 1;
            s.append(isLocal ? "✗ v6 " : "✓ v6 ").append(v6ip);
            if (isLocal) s.append(Lang.t(" ←本机真实地址泄漏",
                    " ←real device addr leaked", " ←実アドレス漏洩"));
            s.append('\n');
        }
        String uip = ud.get("ip"), ucc = ud.get("cc"), uvia = ud.get("via");
        if (uip == null) s.append("— udp err\n");
        else if ("timeout".equals(uip) || "err".equals(uip))
            s.append("✓ udp·stun ").append(uip)
             .append(Lang.t(" ←出站 UDP 不通=无泄面(语音可能不可用)",
                     " ←UDP blocked=no leak surface (voice may fail)",
                     " ←UDP 遮断")).append('\n');
        else if (uip.equals(egIp))
            s.append("✓ udp·stun ").append(uip)
             .append(Lang.t(" 与 TCP 同出口", " same egress as TCP",
                     " TCP と同出口")).append('\n');
        else {
            boolean same = ucc != null && egCc != null
                    && ucc.equalsIgnoreCase(egCc);
            s.append(same ? "✓ udp·stun " : "✗ udp·stun ")
             .append(uip).append(' ')
             .append(ucc == null ? "?" : ucc)
             .append(same ? "" : Lang.t(
                     " ←STUN 见真出口(语音 srflx 带真 IP)",
                     " ←STUN sees real egress (voice srflx=real IP)",
                     " ←STUN 実出口"))
             .append(uvia == null || uvia.isEmpty() ? ""
                     : " [" + uvia + "]").append('\n');
        }
        int udpState;
        if (uip == null) udpState = -1;
        else if ("timeout".equals(uip) || "err".equals(uip)) udpState = 1;
        else if (uip.equals(egIp)) udpState = 1;
        else {
            boolean same = ucc != null && egCc != null
                    && ucc.equalsIgnoreCase(egCc);
            udpState = same ? 1 : 0;
        }
        java.util.List<String> cl = new java.util.ArrayList<>();
        for (String line : raw.split("\n"))
            if (line.startsWith("TGT "))
                cl.add(line.substring(4).trim());
        int tgtState = cl.isEmpty() ? -1 : 1;
        if (!cl.isEmpty()) {
            s.append("✓ ").append(targetName).append(Lang.t(" 域→", " domains→", " ドメイン→"))
                    .append(cl.size()).append(Lang.t(" 条规则命中: ", " rules: ", " 件: "));
            StringBuilder g = new StringBuilder();
            for (String e : cl) {
                int i = e.lastIndexOf(',');
                String grp = (i > 0 ? e.substring(i + 1) : e)
                        .replace("\"", "").trim();
                if (g.indexOf(grp) < 0)
                    g.append(g.length() > 0 ? "/" : "").append(grp);
            }
            s.append(g).append(Lang.t("(fake-ip=无外部解析)",
                    " (fake-ip=no external lookup)",
                    " (fake-ip=外部解無し)")).append('\n');
        }
        String lapp = lk.get("app"), ldown = lk.get("down");
        // `settings get` prints the literal string "null" for an unset key.
        if ("null".equals(lapp)) lapp = "";
        if ("null".equals(ldown)) ldown = "";
        String lastVpn = lk.get("vpn");
        String vpnName = lastVpn == null || lastVpn.isEmpty()
                ? (lapp == null || lapp.isEmpty() ? "?" : lapp) : lastVpn;
        boolean locked = "1".equals(ldown);
        s.append(locked ? "✓ " : "⚠ ")
         .append(Lang.t("始终开启 VPN: ", "always-on VPN: ", "常時 VPN: "))
         .append(vpnName)
         .append(Lang.t(" · 阻止非 VPN 连接 ", " · block non-VPN ",
                 " · 非 VPN 遮断 "))
         .append(locked ? Lang.t("开", "on", "ON") : Lang.t("关", "off", "OFF"))
         .append(Lang.t(" · 应用可绕过 VPN ", " · app can bypass ",
                 " · アプリ迂回 "))
         .append("true".equals(lk.get("bypass"))
                 ? Lang.t("是", "yes", "可") : Lang.t("否", "no", "不可"))
         .append('\n');
        return new Result(s.toString(), lastVpn,
                lapp == null ? "" : lapp,
                egIp == null ? "" : egIp,
                egCc == null ? "" : egCc,
                egOrg == null ? "" : egOrg,
                !hasCc || !hasRegion ? -1 : matches ? 1 : 0,
                dcAsn, dnsState, v6State, udpState, tgtState);
    }

    /** parse "KEY k=v k=v" line → map (local='a b' keeps spaces via last k=v). */
    private static java.util.Map<String, String> kv(String raw, String key) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            if (!line.startsWith(key + " ")) continue;
            for (String tok : line.substring(key.length() + 1).split(" ")) {
                int eq = tok.indexOf('=');
                if (eq > 0) m.put(tok.substring(0, eq), tok.substring(eq + 1));
                else if (!m.isEmpty()) {
                    String lastK = null;
                    for (String k : m.keySet()) lastK = k;
                    if (lastK != null) m.put(lastK, m.get(lastK) + " " + tok);
                }
            }
        }
        return m;
    }
}
