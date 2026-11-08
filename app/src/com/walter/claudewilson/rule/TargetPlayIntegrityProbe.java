package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

/** Local Play Integrity material is visible as a store, never as a usable token. */
public final class TargetPlayIntegrityProbe implements Probe {

    @Override public String id() {
        return "target.play-integrity";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "play_integrity_token";
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
        if (!evidence.dataRequested()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE,
                    "token not read; local inventory not requested")
                    .with("boundary", "server-attested")
                    .with("forgery", "not-attempted");
        }
        if (!evidence.dataReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "token not read; local stores unreadable")
                    .with("boundary", "server-attested")
                    .with("forgery", "not-attempted");
        }
        String observed = "token not read · webview="
                + (evidence.webViewStore() ? "store" : "no-store")
                + " · markers=" + (evidence.integrityKinds().isEmpty()
                        ? "none" : String.join(",", evidence.integrityKinds()));
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE, observed)
                .with("boundary", "server-attested")
                .with("forgery", "not-attempted");
    }
}
