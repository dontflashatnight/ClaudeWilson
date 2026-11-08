package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Census of installed packages whose name or label carries tooling keywords.
 *
 * <p>Each package is reported once and keeps every reason it was flagged, so the count is the
 * number of distinct packages rather than the number of keyword hits.
 */
public final class PackageSuspicionProbe implements Probe {

    @Override
    public String id() {
        return "env.package-suspicion";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "installed_apps";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        List<String> nameKeywords = snapshot.tables().packageKeywords();
        List<String> labelKeywords = snapshot.tables().labelKeywords();
        if (nameKeywords.isEmpty() && labelKeywords.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG,
                    "package-keywords");
        }
        if (!snapshot.packagesReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "package list");
        }
        Map<String, List<String>> hits = new LinkedHashMap<String, List<String>>();
        for (String packageName : snapshot.packages()) {
            String name = packageName.toLowerCase(Locale.ROOT);
            for (String keyword : nameKeywords) {
                if (name.contains(keyword)) {
                    record(hits, packageName, "name:" + keyword);
                    break;
                }
            }
            String label = snapshot.labelOf(packageName).toLowerCase(Locale.ROOT);
            if (!label.isEmpty()) {
                for (String keyword : labelKeywords) {
                    if (label.contains(keyword)) {
                        record(hits, packageName, "label:" + keyword);
                        break;
                    }
                }
            }
        }
        if (hits.isEmpty()) {
            // An empty census is a result, not an observation: nothing suspicious is installed, so
            // the layer is clean. It used to be INFO (neutral grey), which read as "unknown".
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.ABSENT,
                    snapshot.packages().size() + " packages, 0 flagged")
                    .with("keywords", String.valueOf(nameKeywords.size() + labelKeywords.size()));
        }
        List<String> lines = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : hits.entrySet()) {
            lines.add(entry.getKey() + " (" + String.join(",", entry.getValue()) + ")");
            names.add(entry.getKey());
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.SUSPICIOUS,
                hits.size() + " flagged packages")
                .with("flagged", String.valueOf(hits.size()))
                .with("reasons", String.join("; ", lines))
                .with("total", String.valueOf(snapshot.packages().size()))
                // Names as their own parameter so the renderer can attach an action per package
                // instead of parsing them back out of prose.
                .with("packages", String.join(",", names))
                .with("catalog", snapshot.tables().version());
    }

    private static void record(Map<String, List<String>> hits, String packageName, String why) {
        List<String> reasons = hits.get(packageName);
        if (reasons == null) {
            reasons = new ArrayList<String>();
            hits.put(packageName, reasons);
        }
        if (!reasons.contains(why)) {
            reasons.add(why);
        }
    }
}
