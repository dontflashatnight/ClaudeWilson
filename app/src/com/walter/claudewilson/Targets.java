package com.walter.claudewilson;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.walter.claudewilson.model.AuditedBaselines;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String BASELINES_ASSET = "catalog/audited-targets.txt";

    private static volatile TargetSpec selected = TargetRegistry.CLAUDE;
    private static volatile Map<String, AuditedBaselines.Baseline> baselines = Collections.emptyMap();
    private static final Map<String, State> STATES = new LinkedHashMap<String, State>();

    private Targets() {
    }

    static void init(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, 0);
        selected = TargetRegistry.byId(prefs.getString(KEY, null));
        baselines = loadBaselines(app);
    }

    private static Map<String, AuditedBaselines.Baseline> loadBaselines(Context app) {
        try (InputStream in = app.getAssets().open(BASELINES_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return AuditedBaselines.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
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
            long versionCode = 0L;
            String note = "";
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(spec.packageName(), 0);
                installed = true;
                version = info.versionName == null ? "" : info.versionName;
                versionCode = info.getLongVersionCode();
            } catch (Throwable t) {
                note = "root-fallback";
                // The target may be installed but omitted from this app's package-visibility
                // view (observed on a One UI build even with QUERY_ALL_PACKAGES granted).
                // The product requires a privileged channel anyway, so the
                // shell's package state is the authoritative fallback; no inference from a
                // missing version alone.
                if (privileged) {
                    String fromShell = actions.installedVersion(spec);
                    long shellCode = actions.installedVersionCode(spec);
                    if (!fromShell.isEmpty() || shellCode > 0L) {
                        installed = true;
                        version = fromShell;
                        versionCode = shellCode;
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
            AuditedBaselines.Baseline baseline = baselines.get(spec.id());
            AuditedBaselines.Drift drift = AuditedBaselines.assess(
                    installed, versionCode, version, baseline);
            List<String> unseenKeys = TelemetryStore.unseenKeys(app, spec.id());
            STATES.put(spec.id(), new State(true, installed, version, versionCode, config, note,
                    baseline, drift, unseenKeys));
        }
    }

    static final class State {
        static final State UNKNOWN = new State(false, false, "", 0L, false, "",
                null, AuditedBaselines.Drift.UNKNOWN, Collections.<String>emptyList());

        final boolean checked;
        final boolean installed;
        final String version;
        final long versionCode;
        final boolean config;
        final String note;
        final AuditedBaselines.Baseline baseline;
        final AuditedBaselines.Drift drift;
        final List<String> unseenKeys;

        State(boolean checked, boolean installed, String version, long versionCode,
              boolean config, String note, AuditedBaselines.Baseline baseline,
              AuditedBaselines.Drift drift, List<String> unseenKeys) {
            this.checked = checked;
            this.installed = installed;
            this.version = version;
            this.versionCode = versionCode;
            this.config = config;
            this.note = note;
            this.baseline = baseline;
            this.drift = drift == null ? AuditedBaselines.Drift.UNKNOWN : drift;
            this.unseenKeys = unseenKeys == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(unseenKeys);
        }

        boolean needsAudit() {
            return drift == AuditedBaselines.Drift.UPDATED || !unseenKeys.isEmpty();
        }
    }
}
