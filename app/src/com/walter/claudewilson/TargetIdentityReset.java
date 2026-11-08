package com.walter.claudewilson;

import android.content.Context;

import com.walter.claudewilson.model.ShellQuote;
import com.walter.claudewilson.model.TargetSpec;

/**
 * The one destructive local-identity operation: stop the target, then clear the package so the
 * next launch mints new local IDs.
 *
 * <p>This is deliberately not a per-file UUID rewrite. The analysis' L1 layer is a local data
 * reset; editing one UUID while Sentry/Segment/Datadog keep theirs would be a fake reset.
 */
final class TargetIdentityReset {
    String reset(Context context, TargetSpec target) {
        if (target == null || !target.localIdentityWrites()) {
            return "failed: target does not permit local identity writes";
        }
        if (!ShellQuote.packageName(target.packageName())) {
            return "failed: invalid package";
        }
        if (!RootShell.rootReady()) {
            return "failed: root not ready";
        }
        if (new RootActions(context).targetRunning(target)) {
            return "failed: target is running; stop it first";
        }
        String pkg = target.packageName();
        String out = RootShell.run(DataClean.command(pkg, null), 32768, 120000L);
        if (!DataClean.ok(out)) {
            return "failed: clear incomplete";
        }
        return "ok";
    }
}
