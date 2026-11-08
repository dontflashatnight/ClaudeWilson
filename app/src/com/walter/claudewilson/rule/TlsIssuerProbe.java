package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TlsFacts;

import java.util.ArrayList;
import java.util.List;

/**
 * Certificate authorities that should not be in a system trust store.
 *
 * <p>The list lives in the catalog asset, not in the code, so it can be regenerated without a
 * recompile. A trust store that could not be enumerated is unknown rather than clean.
 */
public final class TlsIssuerProbe implements Probe {

    @Override
    public String id() {
        return "network.tls-issuers";
    }

    @Override
    public Layer layer() {
        return Layer.NETWORK;
    }

    @Override
    public String field() {
        return "incident 10202";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        List<String> leaked = snapshot.tables().leakedCas();
        if (leaked.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG,
                    "leaked-cas");
        }
        TlsFacts tls = snapshot.tls();
        if (!tls.issuersReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "trust store");
        }
        List<String> hits = new ArrayList<String>();
        for (String issuer : tls.issuers()) {
            for (String expected : leaked) {
                if (expected.equalsIgnoreCase(issuer)) {
                    hits.add(issuer);
                    break;
                }
            }
        }
        if (hits.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                    "0/" + leaked.size() + " present (" + tls.issuers().size() + " CAs)")
                    .with("catalog", snapshot.tables().version());
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                String.join("; ", hits))
                .with("hits", String.valueOf(hits.size()));
    }
}
