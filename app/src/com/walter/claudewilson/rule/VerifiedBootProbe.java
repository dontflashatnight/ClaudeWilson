package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.PlatformFacts;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/**
 * Verified Boot state from the boot properties.
 *
 * <p>A locked bootloader reporting {@code green} is the precondition for a device verdict; an
 * unlocked or self-signed state is a finding. Properties that were never collected are unknown,
 * because an absent property and an unread one look the same.
 */
public final class VerifiedBootProbe implements Probe {

    @Override
    public String id() {
        return "platform.verified-boot";
    }

    @Override
    public Layer layer() {
        return Layer.PLATFORM;
    }

    @Override
    public String field() {
        return "integrity prerequisite";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        Text flashLocked = snapshot.prop("ro.boot.flash.locked");
        Text verifiedBoot = snapshot.prop("ro.boot.verifiedbootstate");
        Text deviceState = snapshot.prop("ro.boot.vbmeta.device_state");
        Text oemUnlock = snapshot.prop("sys.oem_unlock_allowed");
        if (!flashLocked.isReadable() && !verifiedBoot.isReadable()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "ro.boot.* not collected");
        }
        String observed = "flash.locked=" + show(flashLocked) + " vbstate=" + show(verifiedBoot)
                + " device_state=" + show(deviceState) + " oem_unlock=" + show(oemUnlock);
        boolean locked = "1".equals(flashLocked.value())
                || "locked".equals(deviceState.value());
        boolean green = "green".equals(verifiedBoot.value());
        if (locked && green) {
            return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, observed);
        }
        if ("orange".equals(verifiedBoot.value()) || "yellow".equals(verifiedBoot.value())) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.INCONSISTENT, observed);
        }
        return Finding.of(id(), layer(), field(), Status.WARN, Reason.INCONSISTENT, observed);
    }

    private static String show(Text value) {
        return value.isReadable() ? (value.isEmpty() ? "(empty)" : value.value()) : "?";
    }
}
