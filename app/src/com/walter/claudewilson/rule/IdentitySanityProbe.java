package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plausibility of the identity fields themselves: a model naming one vendor under another
 * manufacturer's name, or a build time that cannot be real.
 *
 * <p>The future check uses the snapshot's own capture time, not the clock at evaluation time, so
 * a replayed snapshot grades the same way it did when it was taken.
 */
public final class IdentitySanityProbe implements Probe {

    private static final long DAY_MS = 86400000L;

    @Override
    public String id() {
        return "identity.field-sanity";
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
        if (build.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE, "Build.*");
        }
        List<String> vendorWords = snapshot.tables().vendorWords();
        if (vendorWords.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG,
                    "vendor-words");
        }
        String manufacturer = build.manufacturer().toLowerCase(Locale.ROOT);
        String model = build.model().toLowerCase(Locale.ROOT);
        List<String> odd = new ArrayList<String>();
        for (String word : vendorWords) {
            if (!model.contains(word)) {
                continue;
            }
            // A sub-brand in the model is expected when the manufacturer is its parent, and the
            // mapping is data (catalog/vendor-families.txt), not a special case per brand.
            String parent = snapshot.tables().vendorFamilies().get(word);
            if (manufacturer.contains(word) || (parent != null && manufacturer.contains(parent))) {
                continue;
            }
            odd.add("model contains '" + word + "' but manufacturer=" + build.manufacturer());
        }
        long built = build.time();
        long captured = snapshot.capturedAt();
        if (built <= 0) {
            odd.add("Build.TIME not set");
        } else if (captured > 0 && built > captured + DAY_MS) {
            odd.add("Build.TIME in the future");
        }
        Finding finding = odd.isEmpty()
                ? Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, "sane")
                : Finding.of(id(), layer(), field(), Status.WARN, Reason.SUSPICIOUS,
                        String.join("; ", odd));
        return finding
                .with("model", build.model())
                .with("manufacturer", build.manufacturer());
    }
}
