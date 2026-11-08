package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.TlsFacts;

import java.util.List;

/** User-installed certificate authorities, the usual signature of an interception proxy. */
public final class UserCaProbe implements Probe {

    @Override
    public String id() {
        return "network.user-ca";
    }

    @Override
    public Layer layer() {
        return Layer.NETWORK;
    }

    @Override
    public String field() {
        return "MITM indicator";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        TlsFacts tls = snapshot.tls();
        if (!tls.userCaReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "user CA directory");
        }
        List<String> files = tls.userCaFiles();
        if (!files.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                    files.size() + " user CA: " + String.join(", ", files))
                    .with("action", "credential-settings");
        }
        String system = tls.issuersReadable()
                ? tls.issuers().size() + " system CAs"
                : "system store not enumerable";
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                "no user CA (" + system + ")")
                .with("action", "credential-settings");
    }
}
