package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

/** The account/device graph lives on the service; this tool can only name the boundary. */
public final class TargetAccountChainProbe implements Probe {

    @Override public String id() {
        return "target.account-chain";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "account_device_chain";
    }

    @Override public Finding run(Snapshot snapshot) {
        TargetEvidence evidence = snapshot.targetEvidence();
        if (!evidence.collected()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "target evidence not collected");
        }
        if (!evidence.installed()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.ABSENT,
                    "not installed");
        }
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE,
                "server-bound; local account graph not read")
                .with("boundary", "server-side")
                .with("values", "not-read");
    }
}
