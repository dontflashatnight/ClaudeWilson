package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Mount;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * System partitions that are writable, or that carry an overlay / injected source.
 *
 * <p>This is the probe that used to report a pass when {@code /proc/mounts} could not be read:
 * an unreadable table produced an empty result, and an empty result was rendered green. Here an
 * unreadable table is {@link Status#UNKNOWN} and says so.
 */
public final class MountStateProbe implements Probe {

    @Override
    public String id() {
        return "env.mount-state";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "evidence_directories_writable";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        if (!snapshot.mountsReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "/proc/mounts")
                    .with("hint", "mount table not readable, cannot tell read-only from unknown");
        }
        List<String> rwDirs = snapshot.tables().rwDirs();
        if (rwDirs.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.NO_CATALOG, "rw-dirs");
        }
        Set<String> bad = new LinkedHashSet<String>();
        for (Mount mount : snapshot.mounts()) {
            if (rwDirs.contains(mount.mountPoint()) && mount.isReadWrite()) {
                bad.add(mount.mountPoint() + " rw");
            }
            if (isSystemPartition(mount.mountPoint()) && looksInjected(mount)) {
                bad.add(mount.mountPoint() + " <- " + mount.source() + " (" + mount.fsType() + ")");
            }
        }
        if (bad.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK,
                    rwDirs.size() + " dirs read-only")
                    .with("mounts", String.valueOf(snapshot.mounts().size()));
        }
        return Finding.of(id(), layer(), field(), Status.FAIL, Reason.PRESENT,
                String.join(", ", bad))
                .with("hits", String.valueOf(bad.size()));
    }

    private static boolean isSystemPartition(String mountPoint) {
        return "/system".equals(mountPoint) || mountPoint.startsWith("/system/")
                || mountPoint.startsWith("/vendor/");
    }

    private static boolean looksInjected(Mount mount) {
        return mount.source().contains("magisk") || "overlay".equals(mount.fsType())
                || mount.source().contains("tmpfs");
    }
}
