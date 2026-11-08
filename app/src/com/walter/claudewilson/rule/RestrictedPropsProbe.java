package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the debug properties are readable at all.
 *
 * <p>The question is readability, not the value: on a locked build an untrusted app reads these
 * properties as empty, so a plain value means the property access control was loosened
 * (permissive SELinux, modified sepolicy). Empty is the expected answer.
 *
 * <p>A property that was never collected is different from a property observed to be empty, and
 * only the second one is a result; when nothing was collected the finding is unknown.
 */
public final class RestrictedPropsProbe implements Probe {

    private static final String[] KEYS = {"ro.debuggable", "ro.secure"};

    @Override
    public String id() {
        return "env.restricted-props";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "evidence_properties";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        boolean collected = false;
        List<String> visible = new ArrayList<String>();
        List<String> bad = new ArrayList<String>();
        for (String key : KEYS) {
            Text value = snapshot.prop(key);
            if (!value.isReadable()) {
                continue;
            }
            collected = true;
            if (value.isEmpty()) {
                continue;
            }
            visible.add(key + "=" + value.value());
            if (("ro.debuggable".equals(key) && "1".equals(value.value()))
                    || ("ro.secure".equals(key) && "0".equals(value.value()))) {
                bad.add(key + "=" + value.value());
            }
        }
        if (!collected) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "ro.debuggable, ro.secure not collected");
        }
        if (!bad.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                    String.join(", ", bad));
        }
        if (visible.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                    "0/" + KEYS.length + " readable (expected on a locked build)");
        }
        return Finding.of(id(), layer(), field(), Status.WARN, Reason.SUSPICIOUS,
                String.join(", ", visible))
                .with("hint", "values look normal, but a locked build should not expose them");
    }
}
