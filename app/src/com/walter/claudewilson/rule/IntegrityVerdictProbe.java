package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.AttestationFacts;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/**
 * A signal-only inference about whether a Play Integrity token could carry a verdict.
 *
 * <p>The verdict itself is decided server-side and is not readable here, so this combines the
 * observations that correlation suggests matter: an unlocked bootloader or a reimplemented Google
 * stack sink it, and incomplete evidence keeps it at a warning rather than a pass.
 */
public final class IntegrityVerdictProbe implements Probe {

    @Override
    public String id() {
        return "platform.integrity-verdict";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "client_attestation";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        Text flashLocked = snapshot.prop("ro.boot.flash.locked");
        Text verifiedBoot = snapshot.prop("ro.boot.verifiedbootstate");
        Text deviceState = snapshot.prop("ro.boot.vbmeta.device_state");
        if (!flashLocked.isReadable() && !verifiedBoot.isReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "ro.boot.* not collected");
        }
        boolean locked = "1".equals(flashLocked.value())
                || "locked".equals(deviceState.value());
        boolean green = "green".equals(verifiedBoot.value());
        boolean unlocked = "orange".equals(verifiedBoot.value())
                || "yellow".equals(verifiedBoot.value())
                || ("0".equals(flashLocked.value()) && !locked);
        AttestationFacts attestation = snapshot.platform().attestation();
        boolean microg = snapshot.platform().microg();
        String observed = "lock=" + flashLocked.value() + " vb=" + verifiedBoot.value()
                + " microG=" + microg
                + " attest=" + (attestation.available() ? attestation.securityLevel() : "?");
        if (microg || unlocked) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT,
                    observed + " -> token can be minted but the verdict cannot pass");
        }
        if (!locked || !green || !attestation.available()) {
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.INCOMPLETE, observed);
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                observed + " -> hardware chain looks complete (verdict itself is server-side)");
    }
}
