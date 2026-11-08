package com.walter.claudewilson.platform;

import android.util.Log;

/**
 * Logging seam. The old checks swallowed every failure silently, so a green result and a probe
 * that never ran looked identical; here a failure leaves a trace even when the finding is
 * {@code UNKNOWN}.
 */
public final class Diag {
    private static final String TAG = "ClaudeWilson";

    private Diag() {
    }

    public static void info(String scope, String message) {
        Log.i(TAG, scope + ": " + message);
    }

    public static void warn(String scope, String message, Throwable error) {
        Log.w(TAG, scope + ": " + message, error);
    }
}
