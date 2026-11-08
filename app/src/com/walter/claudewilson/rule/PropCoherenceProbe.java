package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.BuildValues;
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
 * Compares the framework view of the build identity with the native property view.
 *
 * <p>A process-level hook can rewrite the framework fields while the property area still holds
 * the real values, so disagreement between the two is itself the signal. When no native
 * property can be read there is nothing to compare, which is unknown rather than clean.
 */
public final class PropCoherenceProbe implements Probe {

    @Override
    public String id() {
        return "identity.build-prop-coherence";
    }

    @Override
    public Layer layer() {
        return Layer.IDENTITY;
    }

    @Override
    public String field() {
        return "spoof_detector";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        BuildValues build = snapshot.build();
        String[][] pairs = {
                {"manufacturer", "ro.product.manufacturer", build.manufacturer()},
                {"brand", "ro.product.brand", build.brand()},
                {"model", "ro.product.model", build.model()},
                {"device", "ro.product.device", build.device()},
                {"product", "ro.product.name", build.product()},
                {"type", "ro.build.type", build.type()},
                {"id", "ro.build.id", build.buildId()},
                {"incremental", "ro.build.version.incremental", build.incremental()},
                {"display", "ro.build.display.id", build.display()},
        };
        int compared = 0;
        List<String> diffs = new ArrayList<String>();
        for (String[] pair : pairs) {
            Text prop = snapshot.prop(pair[1]);
            if (!prop.isReadable() || prop.isEmpty() || pair[2].isEmpty()) {
                continue;
            }
            compared++;
            if (!pair[2].equalsIgnoreCase(prop.value())) {
                diffs.add(pair[0] + " build=" + pair[2] + " prop=" + prop.value());
            }
        }
        if (compared == 0) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "0/" + pairs.length + " native properties readable")
                    .with("pairs", String.valueOf(pairs.length));
        }
        if (diffs.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    compared + "/" + pairs.length + " agree")
                    .with("compared", String.valueOf(compared));
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT,
                String.join("; ", diffs))
                .with("diffs", String.valueOf(diffs.size()))
                .with("compared", String.valueOf(compared));
    }
}
