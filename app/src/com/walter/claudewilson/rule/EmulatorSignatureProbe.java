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
 * Emulated or virtualised device signatures in the build identity.
 *
 * <p>Covers the fingerprint, model, manufacturer, product and device strings. The legacy check
 * additionally stat()ed {@code /dev/goldfish_pipe}; every emulator that exposes that node also
 * carries a signature in the identity strings, so the extra path is not collected.
 */
public final class EmulatorSignatureProbe implements Probe {

    @Override
    public String id() {
        return "env.emulator-signature";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "device_is_emulator";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        List<String> signatures = snapshot.tables().emulatorSignatures();
        if (signatures.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG,
                    "emulator-signatures");
        }
        BuildValues build = snapshot.build();
        if (build.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE, "Build.*");
        }
        String haystack = (build.fingerprint() + " " + build.model() + " "
                + build.manufacturer() + " " + build.product() + " " + build.device())
                .toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<String>();
        for (String signature : signatures) {
            if (haystack.contains(signature)) {
                hits.add(signature);
            }
        }
        if (hits.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT, "no signature")
                    .with("signatures", String.valueOf(signatures.size()));
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.SUSPICIOUS,
                String.join(", ", hits))
                .with("hits", String.valueOf(hits.size()));
    }
}
