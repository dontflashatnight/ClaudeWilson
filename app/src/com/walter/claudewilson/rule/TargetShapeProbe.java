package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

/** Package shape of the selected target: version, splits, UID and which evidence path answered. */
public final class TargetShapeProbe implements Probe {

    @Override public String id() {
        return "target.shape";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "package_shape";
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
        String observed = "v" + (evidence.version().isEmpty() ? "?" : evidence.version())
                + " · splits=" + evidence.splits().size()
                + " · uid=" + (evidence.uid().isEmpty() ? "?" : evidence.uid())
                + " · " + (evidence.source().isEmpty() ? "?" : evidence.source());
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.OK, observed);
    }
}
