package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.List;

/** An active VPN tunnel, visible to the target through the interface table. */
public final class VpnTunnelProbe implements Probe {

    @Override
    public String id() {
        return "network.vpn";
    }

    @Override
    public Layer layer() {
        return Layer.NETWORK;
    }

    @Override
    public String field() {
        return "network_addresses";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        NetworkFacts network = snapshot.network();
        List<String> tunnels = network.tunnelInterfaces();
        if (network.vpnTransport() || !tunnels.isEmpty()) {
            String observed = network.vpnTransport()
                    ? "VPN transport active" + (tunnels.isEmpty() ? "" : ": "
                            + String.join(", ", tunnels))
                    : "tunnel interfaces: " + String.join(", ", tunnels);
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT, observed)
                    .with("action", "vpn-settings");
        }
        if (!network.interfacesReadable() && !network.proxyReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    network.error().isEmpty() ? "interface table" : network.error());
        }
        if (!network.interfacesReadable() || !network.proxyReadable()) {
            // Something was observed and nothing was found, but one of the two sources stayed
            // blind, so the absence is not established.
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.INCOMPLETE,
                    "no tunnel seen, but " + (network.interfacesReadable()
                            ? "the VPN transport is unreadable" : "the interface table is unreadable"));
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                "no tunnel (" + network.interfaceNames().size() + " interfaces)");
    }
}
