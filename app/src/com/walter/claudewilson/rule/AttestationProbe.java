package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.AttestationFacts;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

/**
 * Hardware key attestation.
 *
 * <p>Generating the key is itself the test: if it cannot be generated, the target's trusted-device
 * flow cannot register either, so that is reported as a warning rather than as unknown. When a
 * root-of-trust block is missing the TEE did not assert a locked state, which is weaker evidence
 * than a negative one, so it stays a warning. A parsed state that is unlocked or unverified is a
 * failure.
 */
public final class AttestationProbe implements Probe {

    @Override
    public String id() {
        return "platform.attestation";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "attestation_blob";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        AttestationFacts facts = snapshot.platform().attestation();
        if (!facts.collected()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "attestation not attempted");
        }
        if (!facts.available()) {
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.NOT_AVAILABLE,
                    "key generation failed: " + facts.error())
                    .with("error", facts.error());
        }
        String observed = "level=" + facts.securityLevel()
                + " boot=" + bootName(facts.verifiedBoot())
                + " locked=" + (facts.rootOfTrust() ? String.valueOf(facts.deviceLocked())
                        : "not provided")
                + " os=" + facts.osVersion() + " patch=" + facts.osPatchLevel()
                + " tee_tags=" + facts.teeTags()
                + (facts.deviceProps().isEmpty() ? "" : " declared=" + facts.deviceProps());
        if (!facts.rootOfTrust()) {
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.INCOMPLETE, observed)
                    .with("challenge", facts.challenge());
        }
        if (facts.verifiedBoot() == 0 && facts.deviceLocked()) {
            Status status = facts.softwareLevel() ? Status.WARN : Status.PASS;
            Reason reason = facts.softwareLevel() ? Reason.SUSPICIOUS : Reason.OK;
            return Finding.of(id(), layer(), field(), status, reason, observed)
                    .with("challenge", facts.challenge())
                    .with("hint", facts.softwareLevel() ? "attestation is software level" : "");
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT, observed)
                .with("challenge", facts.challenge());
    }

    private static String bootName(int verifiedBoot) {
        switch (verifiedBoot) {
            case 0:
                return "verified";
            case 1:
                return "selfsigned";
            case 2:
                return "unverified";
            case 3:
                return "failed";
            default:
                return "unknown";
        }
    }
}
