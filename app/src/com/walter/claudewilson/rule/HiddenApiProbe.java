package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/** The hidden-API enforcement flag, which should stay at its default. */
public final class HiddenApiProbe implements Probe {

    @Override
    public String id() {
        return "target.hidden-api";
    }

    @Override
    public Layer layer() {
        return Layer.TARGET;
    }

    @Override
    public String field() {
        return "compat flag";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        Text policy = snapshot.hiddenApiPolicy();
        if (!policy.isReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "hidden_api_policy");
        }
        if ("0".equals(policy.value())) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                    "=0 (unrestricted for non-platform apps)");
        }
        if (policy.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    "not set (default enforcement)");
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, "=" + policy.value());
    }
}
