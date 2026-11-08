package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Installed packages whose name is on the root/hiding-tool list.
 *
 * <p>Both lists come from the denylist asset, so the app ships no package names of its own. Note
 * what this can and cannot see: a KernelSU module is not an APK and has no package name, and the
 * su allow-list lives in a private directory, so a clean result here rules out one class of
 * tooling rather than proving the device is unrooted.
 */
public final class RootPackagesProbe implements Probe {

    @Override
    public String id() {
        return "env.root-packages";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "evidence_packages_present";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        List<String> baseline = snapshot.tables().rootPackages();
        List<String> modern = snapshot.tables().modernPackages();
        if (baseline.isEmpty() && modern.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG,
                    "root-packages");
        }
        if (!snapshot.installedReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "installed package list");
        }
        List<String> hits = new ArrayList<String>();
        for (String packageName : baseline) {
            if (snapshot.installedPackages().contains(packageName)) {
                hits.add(packageName);
            }
        }
        for (String packageName : modern) {
            if (snapshot.installedPackages().contains(packageName)) {
                hits.add(packageName + " (supplementary)");
            }
        }
        if (hits.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                    "0/" + (baseline.size() + modern.size()) + " listed packages installed")
                    .with("catalog", snapshot.tables().version());
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                String.join(", ", hits))
                .with("hits", String.valueOf(hits.size()));
    }
}
