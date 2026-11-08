package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.PlatformFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

/**
 * Whether the Google stack a Play Integrity token depends on is present and genuine.
 *
 * <p>Absent packages are an observation, not an error: the store and GMS are queried directly.
 * A reimplemented stack is recognisable by its 0.x version numbers, and is reported as a failure
 * because such a stack was measured to produce an empty verdict even when it can mint a token.
 */
public final class GmsProbe implements Probe {

    @Override
    public String id() {
        return "platform.gms";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "play_integrity prerequisite";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        PlatformFacts platform = snapshot.platform();
        if (!platform.collected() || !platform.packagesChecked()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "store/GMS queries did not run");
        }
        if (!platform.playStorePresent()) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.NOT_AVAILABLE,
                    "no Play store");
        }
        boolean gmsMissing = platform.gmsVersion().isEmpty();
        String observed = "vending " + platform.playStoreVersion().value()
                + " / gms " + (gmsMissing ? "missing" : platform.gmsVersion().value())
                + (platform.revancedGms() ? " / +revanced.gms" : "");
        if (gmsMissing) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.NOT_AVAILABLE, observed);
        }
        if (platform.microg()) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT, observed);
        }
        if (platform.revancedGms()) {
            // A patched GMS next to the genuine one is a tampering indicator even when the store
            // and GMS versions look normal; the old card printed it and then said "pass".
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.SUSPICIOUS, observed)
                    .with("packages", "app.revanced.android.gms");
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, observed);
    }
}
