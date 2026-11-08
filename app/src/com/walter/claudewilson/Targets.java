package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Selected target and the small amount of per-target state the screen needs.
 *
 * <p>The selection is a UI preference; it never starts the target and never changes the system
 * persona. State is refreshed off the UI thread because a package query and a root path check
 * do not belong in a render pass.
 */
final class Targets {
    private static final String PREFS = "targets";
    private static final String KEY = "selected";

    private static volatile TargetSpec selected = TargetRegistry.CLAUDE;
    private static final Map<String, State> STATES = new LinkedHashMap<String, State>();

    private Targets() {
    }

    static void init(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, 0);
        selected = TargetRegistry.byId(prefs.getString(KEY, null));
    }

    static TargetSpec current() {
        return selected;
    }

    static void select(Context context, TargetSpec spec) {
        if (spec == null || spec == selected) {
            return;
        }
        selected = spec;
        context.getApplicationContext().getSharedPreferences(PREFS, 0)
                .edit().putString(KEY, spec.id()).apply();
    }

    static State state(TargetSpec spec) {
        State state = STATES.get(spec.id());
        return state == null ? State.UNKNOWN : state;
    }

    /** Worker only: package presence/version plus the target's hook payload, when it has one. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        RootActions actions = new RootActions(app);
        boolean privileged = RootShell.shellReady();
        for (TargetSpec spec : TargetRegistry.all()) {
            boolean installed = false;
            String version = "";
            String note = "";
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(spec.packageName(), 0);
                installed = true;
                version = info.versionName == null ? "" : info.versionName;
            } catch (Throwable t) {
                note = "root-fallback";
                // The target may be installed but omitted from this app's package-visibility
                // view (observed on a One UI build even with QUERY_ALL_PACKAGES granted).
                // The product requires a privileged channel anyway, so the
                // shell's package state is the authoritative fallback; no inference from a
                // missing version alone.
                if (privileged) {
                    String fromShell = actions.installedVersion(spec);
                    if (!fromShell.isEmpty()) {
                        installed = true;
                        version = fromShell;
                    }
                    note = "";
                } else {
                    note = "no-channel";
                }
                // Keep the difference available when a package is known to be installed.
                com.walter.claudewilson.platform.Diag.warn("targets",
                        "package query " + spec.packageName(), t);
            }
            boolean config = spec.hooksEnabled() && privileged && actions.configPresent(spec);
            STATES.put(spec.id(), new State(true, installed, version, config, note));
        }
    }

    static final class State {
        static final State UNKNOWN = new State(false, false, "", false, "");

        final boolean checked;
        final boolean installed;
        final String version;
        final boolean config;
        final String note;

        State(boolean checked, boolean installed, String version, boolean config, String note) {
            this.checked = checked;
            this.installed = installed;
            this.version = version;
            this.config = config;
            this.note = note;
        }
    }
}
