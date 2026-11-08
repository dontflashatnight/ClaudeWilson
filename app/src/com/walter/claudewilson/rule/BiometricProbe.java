package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

/**
 * Lock screen plus a strong biometric enrolment, which a re-attestation key requires.
 *
 * <p>The status code is the raw {@code BiometricManager.canAuthenticate} answer; the rule keeps
 * the codes it can name and treats anything else as a plain failure. The action offered is the
 * security settings screen when the device has no lock screen, otherwise biometric enrolment --
 * those are the two ways out.
 */
public final class BiometricProbe implements Probe {

    private static final int SUCCESS = 0;
    private static final int NO_ENROLLED = 11;
    private static final int NO_HARDWARE = 12;

    @Override
    public String id() {
        return "platform.biometric";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "reattest prerequisite";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        if (!snapshot.platform().collected()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "biometric state not collected");
        }
        int status = snapshot.platform().biometricStatus();
        boolean secure = snapshot.platform().deviceSecure();
        if (status == SUCCESS && secure) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    "strong biometric enrolled + lock screen present");
        }
        String why = status == SUCCESS ? ""
                : status == NO_ENROLLED ? "no biometric enrolled"
                : status == NO_HARDWARE ? "no biometric hardware"
                : "canAuthenticate=" + status;
        String observed = (why.isEmpty() ? "lock screen missing" : why)
                + (secure ? "" : " / no lock screen");
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.NOT_AVAILABLE, observed)
                .with("action", secure ? "biometric-enroll" : "security-settings");
    }
}
