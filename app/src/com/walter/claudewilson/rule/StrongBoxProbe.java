package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

/** StrongBox availability, reported as information rather than as a grade. */
public final class StrongBoxProbe implements Probe {

    @Override
    public String id() {
        return "platform.strongbox";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "trusted_device_reattest";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        if (!snapshot.platform().collected()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "platform features not collected");
        }
        if (snapshot.platform().strongbox()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, "available");
        }
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.ABSENT,
                "no StrongBox (falls back to TEE)");
    }
}
