package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/**
 * How the target application was installed.
 *
 * <p>The package id stays in the platform layer: this probe only judges the installer string it
 * is handed, so no target literal leaks into the rules.
 */
public final class TargetInstallProbe implements Probe {

    private static final String PLAY_STORE = "com.android.vending";

    @Override
    public String id() {
        return "target.install-source";
    }

    @Override
    public Layer layer() {
        return Layer.TARGET;
    }

    @Override
    public String field() {
        return "package_installer_or_sideload";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        if (!snapshot.targetChecked()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "target package state not collected");
        }
        if (!snapshot.targetInstalled()) {
            return Finding.of(id(), layer(), field(), Status.INFO, Reason.ABSENT, "not installed")
                    .with("action", "market-install");
        }
        Text installer = snapshot.installer();
        String observed = "v" + snapshot.targetVersion().value() + " installer="
                + (installer.isReadable() ? installer.value() : "?");
        if (!installer.isReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE, observed);
        }
        if (PLAY_STORE.equals(installer.value())) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, observed);
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT, observed)
                .with("action", "market-install");
    }
}
