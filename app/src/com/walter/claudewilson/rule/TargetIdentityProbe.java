package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

import java.util.List;

/**
 * Name-level local identity inventory.
 *
 * <p>A marker in a file name means a store might exist; it does not mean the target uses that
 * value for the identity the service evaluates. A miss is not absence. Consequently this probe
 * is informational and never a pass.
 */
public final class TargetIdentityProbe implements Probe {

    @Override public String id() {
        return "target.identity-stores";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "local_identity_stores";
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
                    "local inventory not requested for this target");
        }
        if (!evidence.dataReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    evidence.note().isEmpty() ? "target data not readable" : evidence.note());
        }
        List<String> kinds = evidence.identityKinds();
        String observed;
        Reason reason;
        if (kinds.isEmpty()) {
            observed = "no name-level markers · files=" + evidence.filesSeen();
            reason = Reason.ABSENT;
        } else {
            observed = String.join(",", kinds) + " · files=" + evidence.filesSeen();
            reason = Reason.PRESENT;
        }
        return Finding.of(id(), layer(), field(), Status.INFO, reason, observed)
                .with("boundary", "file-names-only")
                .with("values", "not-read");
    }
}
