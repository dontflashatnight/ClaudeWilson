package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

/**
 * The target process's own view of the su path, read from its hookcheck.
 *
 * <p>The control app's root channel is not a probe: it necessarily sees su, and that fact says
 * nothing about the target's namespace. Only this finding grades the target's view.
 */
public final class TargetSuViewProbe implements Probe {

    @Override public String id() {
        return "target.su-view";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "system_su_visibility";
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
        if (!evidence.configPresent()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE,
                    "no active payload; target self-check is stale or unavailable");
        }
        if (evidence.suView() == TargetEvidence.SU_HIDDEN) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    "hookcheck: Java/Os view hidden; raw native not verified")
                    .with("scope", "java-os")
                    .with("raw_native", "unverified");
        }
        if (evidence.suView() == TargetEvidence.SU_VISIBLE) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                    "hookcheck: /system/bin/su visible");
        }
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE,
                "target self-check not available");
    }
}
