package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/**
 * The system HTTP proxy.
 *
 * <p>A proxy declared by the VPN client is how tunnel clients normally publish one, so it is
 * informational; a proxy configured on the network is a warning. Not being able to read the
 * setting at all is unknown -- the previous implementation treated that as "no proxy".
 */
public final class ProxyProbe implements Probe {

    @Override
    public String id() {
        return "network.proxy";
    }

    @Override
    public Layer layer() {
        return Layer.NETWORK;
    }

    @Override
    public String field() {
        return "proxy";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        NetworkFacts network = snapshot.network();
        if (!network.proxyReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    network.error().isEmpty() ? "proxy configuration" : network.error());
        }
        Text host = network.proxyHost();
        if (host.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT, "no proxy");
        }
        String observed = host.value() + ":" + network.proxyPort();
        if (network.proxyViaVpn()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    observed + " (declared by the VPN client)");
        }
        return Finding.of(id(), layer(), field(), Status.WARN, Reason.PRESENT, observed)
                .with("action", "wifi-settings");
    }
}
