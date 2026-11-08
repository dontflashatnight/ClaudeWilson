package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Account;
import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.LinkedHashSet;
import java.util.Set;

/** Google accounts visible to the shell account-service view. */
public final class AccountsProbe implements Probe {

    @Override
    public String id() {
        return "account.google-accounts";
    }

    @Override
    public Layer layer() {
        return Layer.ACCOUNT;
    }

    @Override
    public String field() {
        return "google_accounts";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        if (!snapshot.accountsReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE, "");
        }
        Set<String> names = new LinkedHashSet<String>();
        for (Account account : snapshot.accounts()) {
            if ("com.google".equals(account.type()) && !account.name().isEmpty()) {
                names.add(account.name());
            }
        }
        if (names.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.WARN, Reason.ABSENT, "");
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                String.join("\n", names))
                .with("count", String.valueOf(names.size()));
    }
}
