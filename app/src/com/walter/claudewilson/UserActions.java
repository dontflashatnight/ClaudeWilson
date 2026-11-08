package com.walter.claudewilson;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

/**
 * Commands for the Android user-space controls.
 *
 * <p>New spaces are created as a <b>clone profile of the current user</b>, not as a full
 * secondary user. A clone profile is in the same profile group as its parent, so root's
 * manager can load its app icons without the cross-profile-group permission check that made
 * "manage apps" crash after a full secondary user was created.
 *
 * <p>User ids arrive from an editable field and are therefore validated before they are
 * concatenated into a shell command; an invalid id yields a command that prints a refusal
 * instead of one that runs something else.
 */
final class UserActions {
    private UserActions() {}

    private static String guard(String userId, String command) {
        return ShellQuote.numeric(userId) ? command : "echo refused: invalid user id";
    }

    static String createSpace(String profileOf) {
        if (!ShellQuote.numeric(profileOf)) {
            return "echo refused: invalid profile user id";
        }
        String name = "ws-" + (System.currentTimeMillis() % 100000);
        String create = "pm create-user --profileOf " + profileOf
                + " --user-type android.os.usertype.profile.CLONE " + name;
        // Secure Folder already occupies one profile slot; on a 2-user limit the clone
        // profile is refused until fw.max_users is raised. This needs the system channel
        // (root root); a shell-only channel will show the setprop failure instead.
        return create + " || (setprop fw.max_users 8"
                + " && touch /data/local/tmp/fwmu_bumped && " + create + ")";
    }

    static String listUsers() { return "pm list users"; }

    static String installExisting(String userId, TargetSpec target) {
        if (target == null) return "echo refused: no target";
        return guard(userId, "pm install-existing --user " + userId
                + " " + target.packageName() + "; pm install-existing --user " + userId
                + " com.walter.claudewilson");
    }

    static String clearTarget(String userId, TargetSpec target) {
        if (target == null) return "echo refused: no target";
        return guard(userId, "pm clear --user " + userId + " " + target.packageName());
    }

    /** Clone profiles run alongside the parent user; start, do not switch the foreground. */
    static String startSpace(String userId) {
        return guard(userId, "am start-user " + userId);
    }

    /**
     * Rewrites the per-app SSAID for the target in one user.
     *
     * <p>The previous file is backed up on first touch and the replacement is written to a temp
     * file and moved into place, so a failure between decode and encode cannot leave a truncated
     * XML behind. The result is read back before the backup is dropped.
     */
    static String randomizeId(String userId, TargetSpec target) {
        if (target == null || !target.localIdentityWrites()) {
            return "echo refused: target has no local identity support";
        }
        return guard(userId, com.walter.claudewilson.model.Command.su()
                .raw(com.walter.claudewilson.model.RootScript.randomizeSsaid(
                        userId, target.packageName()))
                .build());
    }
}
