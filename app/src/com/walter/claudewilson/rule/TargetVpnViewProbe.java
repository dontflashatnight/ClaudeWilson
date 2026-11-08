package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

import java.util.List;

/** What the selected target's process would see for VPN/proxy, kept informational. */
public final class TargetVpnViewProbe implements Probe {

    @Override public String id() {
        return "target.vpn-view";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "target_network_view";
    }

    @Override public Finding run(Snapshot snapshot) {
        NetworkFacts network = snapshot.network();
        if (!network.interfacesReadable() && !network.proxyReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    network.error().isEmpty() ? "network view unreadable" : network.error());
        }
        List<String> tunnels = network.tunnelInterfaces();
        Text proxy = network.proxyHost();
        String proxyText = proxy.isReadable()
                ? (proxy.isEmpty() ? "none" : proxy.value() + ":" + network.proxyPort())
                : "?";
        String observed = "transport=" + (network.vpnTransport() ? "vpn" : "none")
                + " · tunnels=" + (tunnels.isEmpty() ? "none" : String.join(",", tunnels))
                + " · proxy=" + proxyText;
        Reason reason = network.vpnTransport() || !tunnels.isEmpty()
                || !proxy.isEmpty() ? Reason.PRESENT : Reason.ABSENT;
        return Finding.of(id(), layer(), field(), Status.INFO, reason, observed)
                .with("scope", "client-view-only");
    }
}
