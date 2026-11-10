package com.walter.claudewilson.hook;

import android.content.Context;

import de.robv.android.xposed.XposedHelpers;

/** Shared target-process context lookup for the hook families. */
final class HookContext {
    private HookContext() {
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
