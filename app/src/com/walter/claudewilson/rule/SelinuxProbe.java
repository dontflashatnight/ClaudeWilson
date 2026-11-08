package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/**
 * SELinux mode.
 *
 * <p>A denied read is treated as the normal locked-build answer: the UI marks it pass while keeping
 * the explicit "unreadable" reason.
 */
public final class SelinuxProbe implements Probe {

    @Override
    public String id() {
        return "env.selinux";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "runtime";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        Text value = snapshot.selinuxEnforce();
        if (!value.isReadable() || value.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.UNREADABLE,
                    "selinuxfs + getenforce");
        }
        String mode = value.value();
        if ("1".equals(mode) || "enforcing".equalsIgnoreCase(mode)) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, "Enforcing");
        }
        if ("0".equals(mode) || "permissive".equalsIgnoreCase(mode)) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT, "Permissive");
        }
        return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNPARSEABLE, mode);
    }
}
