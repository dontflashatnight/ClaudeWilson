package com.walter.claudewilson;

import com.walter.claudewilson.model.TargetSpec;

/** Shell-side egress, routing, and safe remediation operations. The app itself never opens a socket. */
final class NetworkActions {
    private NetworkActions() {}

    static String probe(String selfPackage, TargetSpec target) {
        boolean root = RootShell.rootReady();
        String targetPkg = target == null ? "" : target.packageName();
        String uidLookup = targetPkg.isEmpty()
                ? "TUID=''; "
                : "TUID=$(pm list packages -U " + targetPkg
                        + " 2>/dev/null | sed -n 's/.*uid://p' | head -1); ";
        String tproxyAudit = root
                ? "DR=$(iptables -t nat -S 2>/dev/null | grep -cE -- '--dport 53( |$)'); "
                        + "TP1=$(iptables -t nat -S 2>/dev/null | grep -cE -- '-j (REDIRECT|TPROXY)( |$)'); "
                        + "TP2=$(iptables -t mangle -S 2>/dev/null | grep -cE -- '-j TPROXY( |$)'); "
                        + "TPTCP=$((TP1+TP2)); "
                        + "UBYP=0; "
                        + "if [ -n \"$TUID\" ]; then "
                        + "U1=$(iptables -t nat -S 2>/dev/null | grep -cE -- \"--uid-owner $TUID( |$).* -j RETURN\"); "
                        + "U2=$(iptables -t mangle -S 2>/dev/null | grep -cE -- \"--uid-owner $TUID( |$).* -j RETURN\"); "
                        + "UBYP=$((U1+U2)); "
                        + "fi; "
                : "DR=0; TPTCP=0; UBYP=0; ";
        String script =
                "APK=$(pm path " + selfPackage + " | cut -d: -f2); "
                + "CONN=$(dumpsys connectivity 2>/dev/null"
                + " | grep -E 'NetworkAgentInfo|HttpProxy:|VPN:|bypassable='); "
                + "PX=$(printf '%s\\n' \"$CONN\" | grep -o 'HttpProxy: \\[[^]]*\\] [0-9]*' | head -1); "
                + "PH=$(echo \"$PX\" | sed -n 's/.*\\[\\([^]]*\\)\\].*/\\1/p'); "
                + "PP=$(echo \"$PX\" | sed -n 's/.*\\] \\([0-9]*\\).*/\\1/p'); "
                + "PROXY=; [ -n \"$PH\" ] && [ -n \"$PP\" ] &&"
                + " PROXY=\"-Dhttps.proxyHost=$PH -Dhttps.proxyPort=$PP"
                + " -Dhttp.proxyHost=$PH -Dhttp.proxyPort=$PP\"; "
                + "app_process $PROXY -Djava.class.path=$APK /system/bin "
                + "com.walter.claudewilson.Bridge net; "
                + "D=$(printf '%s\\n' \"$CONN\" | grep -o 'VPN:[A-Za-z0-9_.]*' | head -1 | cut -d: -f2); "
                + "B=$(printf '%s\\n' \"$CONN\" | grep -o 'bypassable=[a-z]*' | head -1 | cut -d= -f2); "
                + "VLINE=$(printf '%s\\n' \"$CONN\" | grep 'NetworkAgentInfo'"
                + " | grep -E 'Transports:[^]]*VPN' | head -1); "
                + "VR=$(printf '%s\\n' \"$VLINE\" | sed -n 's/.*UidRanges: \\(\\[[^]]*\\]\\).*/\\1/p'"
                + " | tr -d ' {}[]<>'); "
                + "if [ -z \"$VR\" ]; then "
                + "VR=$(printf '%s\\n' \"$VLINE\" | sed -n 's/.*Uids: <*\\({[^}]*}\\).*/\\1/p'"
                + " | tr -d ' {}[]<>'); "
                + "fi; "
                + uidLookup
                + tproxyAudit
                + "echo LOCK app=$(settings get secure always_on_vpn_app)"
                + " down=$(settings get secure always_on_vpn_lockdown)"
                + " vpn=$D bypass=$B"
                + " pdns=$(settings get global private_dns_mode)"
                + " pdnsspec=$(settings get global private_dns_specifier)"
                + " gproxy=$(settings get global http_proxy)"
                + " dns53=$DR tptcp=$TPTCP; "
                + "echo \"ROUTE uid=$TUID uidbypass=$UBYP uidranges=$VR\"";
        if (root) {
            return RootShell.runRoot(script, 4096, 30000L);
        }
        return RootShell.run(script, 4096, 30000L);
    }

    /** Clears leftover Settings.Global.http_proxy entries that leak proxy metadata. */
    static String fixCommand(String pkg) {
        return "P=" + pkg + "; "
                + "settings delete global http_proxy >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_host >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_port >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_exclusion_list >/dev/null 2>&1; "
                + "settings delete global global_http_proxy_pac >/dev/null 2>&1; "
                + "echo \"FIX proxy=$(settings get global http_proxy)\""
                + " \"ao=$(settings get secure always_on_vpn_app)\""
                + " \"vpnnets=$(dumpsys connectivity | grep -c 'VPN:')\"";
    }

    /** Disables custom Private DNS (DoT) that bypasses VPN / TProxy DNS interception. */
    static String disablePrivateDnsCommand() {
        return "settings put global private_dns_mode off >/dev/null 2>&1; "
                + "echo \"PDNS mode=$(settings get global private_dns_mode)\"";
    }
}
