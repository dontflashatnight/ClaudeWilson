package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TargetEvidence;

/** hCaptcha state is a web challenge, not a local device field. */
public final class TargetCaptchaProbe implements Probe {

    @Override public String id() {
        return "target.hcaptcha";
    }

    @Override public Layer layer() {
        return Layer.TARGET;
    }

    @Override public String field() {
        return "hcaptcha_state";
    }

    @Override public Finding run(Snapshot snapshot) {
        TargetEvidence evidence = snapshot.targetEvidence();
        if (!evidence.collected()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "target evidence not collected");
        }
        if (!evidence.installed()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.ABSENT,
                    "not installed");
        }
        if (!evidence.dataRequested()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE,
                    "challenge not read; local inventory not requested")
                    .with("boundary", "web-challenge")
                    .with("forgery", "not-attempted");
        }
        if (!evidence.dataReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "challenge not read; local stores unreadable")
                    .with("boundary", "web-challenge")
                    .with("forgery", "not-attempted");
        }
        String observed = "challenge not read · webview="
                + (evidence.webViewStore() ? "store" : "no-store")
                + " · cookies=" + (evidence.cookieStore() ? "store" : "no-store")
                + " · local=" + (evidence.localStorageStore() ? "store" : "no-store");
        return Finding.of(id(), layer(), field(), Status.INFO, Reason.NOT_AVAILABLE, observed)
                .with("boundary", "web-challenge")
                .with("forgery", "not-attempted");
    }
}
