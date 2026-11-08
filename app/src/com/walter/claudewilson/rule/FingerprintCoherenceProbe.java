package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.BuildValues;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Fp;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal coherence of the build fingerprint against the individual build fields, i.e. the
 * signature of an identity rewritten in one layer but not the other.
 */
public final class FingerprintCoherenceProbe implements Probe {

    @Override
    public String id() {
        return "identity.fingerprint-coherence";
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
        if (build.fingerprint().isEmpty() && build.brand().isEmpty() && build.model().isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "Build.FINGERPRINT");
        }
        Fp fp = Fp.parse(build.fingerprint());
        if (fp == null) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNPARSEABLE,
                    build.fingerprint());
        }
        List<String> diffs = new ArrayList<String>();
        compare(diffs, "brand", fp.brand(), build.brand());
        compare(diffs, "product", fp.product(), build.product());
        compare(diffs, "device", fp.device(), build.device());
        compare(diffs, "id", fp.buildId(), build.buildId());
        String incremental = build.incremental();
        // Some vendors append a vendor-specific suffix to the incremental inside the fingerprint
        // (Samsung's `<PDA>_<CSC>` is the common case). The rule accepts any `<incremental>_<x>`
        // rather than assuming a brand, so it stays correct on other vendors.
        if (!incremental.isEmpty() && !incremental.equalsIgnoreCase(fp.incremental())
                && !startsWithIgnoreCase(fp.incremental(), incremental + "_")) {
            diffs.add("incremental fingerprint=" + fp.incremental() + " build=" + incremental);
        }
        if ("user".equals(fp.type()) && "release-keys".equals(fp.tags())
                && !"user".equals(build.type())) {
            diffs.add("fingerprint declares user/release-keys but Build.TYPE=" + build.type());
        }
        if (diffs.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, "coherent");
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT,
                String.join("; ", diffs))
                .with("diffs", String.valueOf(diffs.size()));
    }

    private static void compare(List<String> diffs, String name, String fingerprintValue,
                                String buildValue) {
        if (buildValue.isEmpty() || fingerprintValue.isEmpty()) {
            return;
        }
        if (!fingerprintValue.equalsIgnoreCase(buildValue)) {
            diffs.add(name + " fingerprint=" + fingerprintValue + " build=" + buildValue);
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .startsWith(prefix.toLowerCase(java.util.Locale.ROOT));
    }
}
