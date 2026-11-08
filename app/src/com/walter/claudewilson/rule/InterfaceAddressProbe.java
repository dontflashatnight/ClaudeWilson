package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.NetworkFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.List;

/**
 * The interface table as the target's telemetry would see it. Informational: it reports what is
 * there. A denied enumeration is the normal untrusted-app answer, so it stays explicitly
 * "unreadable" while still counting as the expected result.
 */
public final class InterfaceAddressProbe implements Probe {

    @Override
    public String id() {
        return "network.interfaces";
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
        if (!network.interfacesReadable()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.UNREADABLE,
                    network.error().isEmpty() ? "interface table" : network.error());
        }
        List<String> addresses = network.addressLines();
        if (addresses.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.ABSENT,
                    "no active interface");
        }
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.OK,
                String.join("; ", addresses))
                .with("count", String.valueOf(addresses.size()));
    }
}
