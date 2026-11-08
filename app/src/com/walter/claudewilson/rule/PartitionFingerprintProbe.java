package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Fp;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-checks the per-partition build fingerprints against the main one.
 *
 * <p>A rewrite that only covers {@code ro.build.*} / {@code ro.product.*} leaves the partition
 * copies holding the real identity, which is why they are compared on the brand and product
 * segments; the device segment legitimately differs on QSSI images.
 */
public final class PartitionFingerprintProbe implements Probe {

    private static final String[] PARTITIONS = {
            "ro.system.build.fingerprint",
            "ro.product.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.bootimage.build.fingerprint",
    };

    @Override
    public String id() {
        return "identity.partition-fingerprints";
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
        Fp base = Fp.parse(build.fingerprint());
        if (base == null) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNPARSEABLE,
                    build.fingerprint());
        }
        int readable = 0;
        List<String> diffs = new ArrayList<String>();
        for (String key : PARTITIONS) {
            Text prop = snapshot.prop(key);
            if (!prop.isReadable() || prop.isEmpty()) {
                continue;
            }
            readable++;
            Fp partition = Fp.parse(prop.value());
            if (partition == null) {
                diffs.add(key + " unparseable");
                continue;
            }
            if (!partition.brand().equalsIgnoreCase(base.brand())
                    || !partition.product().equalsIgnoreCase(base.product())) {
                diffs.add(key + " " + partition.brand() + "/" + partition.product()
                        + " vs " + base.brand() + "/" + base.product());
            }
        }
        if (readable == 0) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "0/" + PARTITIONS.length + " partition fingerprints");
        }
        if (diffs.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    readable + "/" + PARTITIONS.length + " agree")
                    .with("compared", String.valueOf(readable));
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT,
                String.join("; ", diffs))
                .with("diffs", String.valueOf(diffs.size()));
    }
}
