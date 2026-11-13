package com.walter.claudewilson;

import com.walter.claudewilson.model.TargetSpec;

/** Existing shell-side egress operations. The app itself never opens a socket. */
final class NetworkActions {
    private NetworkActions() {}

    static String probe(String selfPackage, TargetSpec target) {
        String rule = target == null ? "" : target.domainRule();
        String targetRules = rule.isEmpty() ? "echo TGT none"
                : "su -c 'grep -hE \"DOMAIN-SUFFIX," + rule + "\""
                + " /data/data/*/files/config.yaml"
                + " 2>/dev/null' | sed 's/^/TGT /'";
        String script =
                "APK=$(pm path " + selfPackage + " | cut -d: -f2); "
                // app_process does not consult Android's per-network proxy. With Clash's
                // fake-IP tun the direct connection is accepted by DNS but never completes;
                // feeding the VPN-declared HttpProxy makes the probe use the real egress.
                + "PX=$(dumpsys connectivity 2>/dev/null"
                + " | grep -o 'HttpProxy: \\[[^]]*\\] [0-9]*' | head -1); "
                + "PH=$(echo \"$PX\" | sed -n 's/.*\\[\\([^]]*\\)\\].*/\\1/p'); "
                + "PP=$(echo \"$PX\" | sed -n 's/.*\\] \\([0-9]*\\).*/\\1/p'); "
                + "PROXY=; [ -n \"$PH\" ] && [ -n \"$PP\" ] &&"
                + " PROXY=\"-Dhttps.proxyHost=$PH -Dhttps.proxyPort=$PP"
                + " -Dhttp.proxyHost=$PH -Dhttp.proxyPort=$PP\"; "
                + "app_process $PROXY -Djava.class.path=$APK /system/bin "
                + "com.walter.claudewilson.Bridge net; "
                + "D=$(dumpsys connectivity | grep -o 'VPN:[A-Za-z0-9_.]*'"
                + " | head -1 | cut -d: -f2); "
                + "B=$(dumpsys connectivity | grep -o 'bypassable=[a-z]*'"
                + " | head -1 | cut -d= -f2); "
                + "echo LOCK app=$(settings get secure always_on_vpn_app)"
                + " down=$(settings get secure always_on_vpn_lockdown)"
                + " vpn=$D bypass=$B"
                + " pdns=$(settings get global private_dns_mode); "
                + targetRules;
        // Root runs in the shell domain; app_process there has no usable network even though
        // uid=0. su gives the probe its own domain, which is what the manual probe used. A
        // root-started Shizuku is already in a root domain and has no su to wrap with.
        if (RootShell.rootReady()) {
            return RootShell.runRoot(script, 4096, 45000L);
        }
        return RootShell.run(script, 4096, 45000L);
    }

    /**
     * What this button can change from a shell uid, then one machine-readable read-back:
     *
     * <ol>
     *   <li>IPv6 off — {@code sysctl}; needs root, fails loudly otherwise</li>
     *   <li>the global proxy entries (http_proxy, host, port, exclusion list, PAC)</li>
     * </ol>
     *
     * <p><b>Always-on VPN is deliberately not written.</b> Android 12+ keeps that state in
     * {@code VpnProfileStore} and only exposes {@code IConnectivityManager.setAlwaysOnVpnPackage}
     * to holders of CONTROL_VPN. Writing {@code Settings.Secure.ALWAYS_ON_VPN_APP} (the pre-11
     * path) does not take effect at runtime: on this device the secure key read back as the package while the VPN
     * settings screen still showed the switch off and {@code /data/misc/vpn/profiles} was absent.
     * The verdict points at the manual switch instead of claiming success.
     *
     * <p>Private DNS stays untouched too: with a VpnService up the platform owns DNS and the
     * setting reads {@code off}.
     */
    static String fixCommand(String pkg) {
        return "P=" + pkg + "; "
                + "su -c 'sysctl -w net.ipv6.conf.all.disable_ipv6=1' >/dev/null 2>&1; "
                + "settings delete global http_proxy >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_host >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_port >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_exclusion_list >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_pac >/dev/null 2>&1; "
                + "echo \"FIX proxy=$(settings get global http_proxy)\""
                + " \"v6=$(sysctl -n net.ipv6.conf.all.disable_ipv6 2>/dev/null || echo ?)\""
                + " \"v6addr=$(ip -o -6 addr show scope global 2>/dev/null | wc -l)\""
                + " \"ao=$(settings get secure always_on_vpn_app)\""
                // 0 = no VpnService network is up: on kernel/tproxy setups this is normal.
                + " \"vpnnets=$(dumpsys connectivity | grep -c 'VPN:')\"";
    }
}
