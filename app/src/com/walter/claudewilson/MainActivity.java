package com.walter.claudewilson;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Lifecycle and runtime permissions only. */
public final class MainActivity extends Activity {
    private static final String[] PERMISSIONS = {
            android.Manifest.permission.READ_PHONE_STATE};
    private Dashboard dashboard;
    private RootShell.Subscription rootSubscription;
    private android.content.BroadcastReceiver radio;
    private final android.os.Handler refreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean firstResume = true;
    private final Runnable delayedTargetRefresh = () -> {
        if (dashboard != null) dashboard.startScan(true);
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        installCrashLog();
        dashboard = new Dashboard(this);
        dashboard.show();
        watchRadio();
        rootSubscription = RootShell.observe(
                granted -> ScreenUpdates.post(screen -> screen.onRootPermission(granted)),
                // Whole screen, not just the region card: the root state is exactly what a
                // root-manager grant or revocation invalidates.
                () -> ScreenUpdates.post(Dashboard::refresh));
        if (missingPermissions()) requestPermissions(PERMISSIONS, 7);
        else dashboard.rescan();
    }

    /**
     * Keeps the visited-network row alive across radio events.
     *
     * <p>{@code gsm.operator.*} mirrors the modem: airplane mode off, a re-registration or a SIM
     * change rewrites it with the real PLMN. Listening here means the persona trio is put back
     * without the user having to tap the region again.
     */
    private void watchRadio() {
        radio = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context,
                                            android.content.Intent intent) {
                String action = intent.getAction();
                boolean force = android.telephony.SubscriptionManager
                        .ACTION_DEFAULT_SUBSCRIPTION_CHANGED.equals(action)
                        || "android.intent.action.SIM_STATE_CHANGED".equals(action);
                ScreenUpdates.post(screen -> screen.radioChanged(force));
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction("android.intent.action.SERVICE_STATE");
        filter.addAction(android.telephony.SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(radio, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(radio, filter);
        }
    }

    /**
     * Leaves a stack trace in {@code files/last-crash.txt}. The platform crash buffer is not
     * reliable on every OEM build (a crash here produced no logcat entry at all), so the app keeps
     * its own record; it is overwritten on every crash and never leaves the private dir.
     */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.PrintWriter out = new java.io.PrintWriter(
                        new java.io.FileWriter(new java.io.File(getFilesDir(), "last-crash.txt"),
                                false));
                out.println(new java.util.Date() + " thread=" + thread.getName());
                error.printStackTrace(out);
                out.close();
            } catch (Throwable ignored) {
                // A failing crash logger must not mask the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private boolean missingPermissions() {
        for (String permission : PERMISSIONS)
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                return true;
        return false;
    }

    @Override protected void onResume() {
        super.onResume();
        // The active data subscription can change while the app is backgrounded; bring the
        // visited-network row back immediately instead of waiting for the next service state.
        if (dashboard != null) dashboard.radioChanged(true);
        if (firstResume) {
            firstResume = false;
            return;
        }
        // Returning from the target: read its self-check now and once more after the module's
        // 2.5s self-check window. This is what turns target.* from last-launch values into the
        // current process view without a manual pull.
        refreshHandler.removeCallbacks(delayedTargetRefresh);
        dashboard.startScan(true);
        refreshHandler.postDelayed(delayedTargetRefresh, 3200L);
    }

    @Override protected void onPause() {
        refreshHandler.removeCallbacks(delayedTargetRefresh);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        dashboard.rescan();
    }

    @Override protected void onDestroy() {
        if (radio != null) {
            try { unregisterReceiver(radio); } catch (Throwable ignored) {}
            radio = null;
        }
        if (rootSubscription != null) rootSubscription.close();
        if (dashboard != null) dashboard.close();
        super.onDestroy();
    }
}
