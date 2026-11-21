package com.walter.claudewilson.hook;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

/**
 * Shared target-process context and immutable host Android version snapshot.
 *
 * <p>{@link #HOST_SDK_INT}, {@link #HOST_SDK_INT_FULL}, and {@link #HOST_RELEASE} capture the real
 * host OS values before any hook mutates {@link Build.VERSION}. Hooks and internal runtime guards
 * must always branch on {@link #HOST_SDK_INT} rather than {@link Build.VERSION#SDK_INT}.
 */
final class HookContext {
    static final int HOST_SDK_INT = Build.VERSION.SDK_INT;
    static final int HOST_SDK_INT_FULL = readHostSdkIntFull();
    static final String HOST_RELEASE =
            Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : String.valueOf(HOST_SDK_INT);
    static final String HOST_CODENAME =
            Build.VERSION.CODENAME != null ? Build.VERSION.CODENAME : "REL";
    static final String HOST_RELEASE_OR_CODENAME =
            "REL".equals(HOST_CODENAME) ? HOST_RELEASE : HOST_CODENAME;

    private HookContext() {
    }

    /** Ensure class initialization snapshots host Build.VERSION before BuildHooks mutates fields. */
    static void init() {
        // Accessing any static member triggers <clinit>; method exists for explicit ordering.
    }

    private static int readHostSdkIntFull() {
        try {
            Field field = Build.VERSION.class.getField("SDK_INT_FULL");
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Format {@link #HOST_SDK_INT_FULL} into the {@code ro.build.version.sdk_full} string form
     * (e.g. {@code 3600000 -> "36.0"}, {@code 3600001 -> "36.1"}). Returns {@code null} on API
     * levels prior to 36 where {@code SDK_INT_FULL} does not exist.
     */
    static String hostSdkFullProperty() {
        if (HOST_SDK_INT_FULL <= 0) {
            return null;
        }
        int major = HOST_SDK_INT_FULL / 100000;
        int minor = HOST_SDK_INT_FULL % 100000;
        return major + "." + minor;
    }

    static Context app(ClassLoader loader) {
        try {
            Class<?> thread = XposedHelpers.findClass("android.app.ActivityThread", loader);
            Object application = XposedHelpers.callStaticMethod(thread, "currentApplication");
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
