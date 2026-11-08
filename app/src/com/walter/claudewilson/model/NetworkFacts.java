package com.walter.claudewilson.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Network interface and proxy facts.
 *
 * <p>The two sources are independent and fail independently: the interface table comes from
 * {@code NetworkInterface} (null on devices where SELinux denies the netlink socket) while the
 * VPN transport and the proxy come from {@code ConnectivityManager}. Keeping one flag per source
 * is what stops a failure in one from erasing the answer from the other, which is exactly what
 * used to happen -- a single swallowing catch turned both into "nothing found".
 */
public final class NetworkFacts {
    public static final NetworkFacts EMPTY = new NetworkFacts(false, false, "", false,
            Collections.<String>emptyList(), Collections.<String>emptyList(),
            Text.unreadable(), -1, false);

    private final boolean interfacesReadable;
    private final boolean proxyReadable;
    private final String error;
    private final boolean vpnTransport;
    private final List<String> interfaceNames;
    private final List<String> addressLines;
    private final Text proxyHost;
    private final int proxyPort;
    private final boolean proxyViaVpn;

    public NetworkFacts(boolean interfacesReadable, boolean proxyReadable, String error,
                        boolean vpnTransport, List<String> interfaceNames,
                        List<String> addressLines, Text proxyHost, int proxyPort,
                        boolean proxyViaVpn) {
        this.interfacesReadable = interfacesReadable;
        this.proxyReadable = proxyReadable;
        this.error = error == null ? "" : error;
        this.vpnTransport = vpnTransport;
        this.interfaceNames = copy(interfaceNames);
        this.addressLines = copy(addressLines);
        this.proxyHost = proxyHost == null ? Text.unreadable() : proxyHost;
        this.proxyPort = proxyPort;
        this.proxyViaVpn = proxyViaVpn;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(
                values == null ? new ArrayList<String>() : new ArrayList<String>(values));
    }

    /** True when the interface table could be enumerated. */
    public boolean interfacesReadable() {
        return interfacesReadable;
    }

    /** True when the connectivity service answered (VPN transport + proxy). */
    public boolean proxyReadable() {
        return proxyReadable;
    }

    /** Why collection failed, shown on the card instead of a bare "unknown". */
    public String error() {
        return error;
    }

    public boolean vpnTransport() {
        return vpnTransport;
    }

    public List<String> interfaceNames() {
        return interfaceNames;
    }

    public List<String> addressLines() {
        return addressLines;
    }

    public Text proxyHost() {
        return proxyHost;
    }

    public int proxyPort() {
        return proxyPort;
    }

    /** True when the proxy is declared by the VPN client rather than configured by the user. */
    public boolean proxyViaVpn() {
        return proxyViaVpn;
    }

    /** Tunnel-like interface names, which is how the target's telemetry sees a proxy. */
    public List<String> tunnelInterfaces() {
        List<String> tunnels = new ArrayList<String>();
        for (String name : interfaceNames) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("wg")
                    || lower.startsWith("tap")) {
                tunnels.add(name);
            }
        }
        return tunnels;
    }
}
