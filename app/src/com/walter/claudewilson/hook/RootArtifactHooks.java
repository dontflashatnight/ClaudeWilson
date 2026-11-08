package com.walter.claudewilson.hook;

import android.system.ErrnoException;
import android.system.OsConstants;

import com.walter.claudewilson.model.RootArtifactPolicy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides the root channel's own artifacts from the target process.
 *
 * <p>This is deliberately narrow: only the known su/module paths are hidden, and only from file
 * and command APIs the target uses for root checks. It does not hide the control app's view; the
 * environment card reports that view separately from the target self-check.
 *
 * <p>The target never needs these paths. A hidden path is reported as absent exactly as it would
 * be on an unrooted device; no other file is touched.
 */
final class RootArtifactHooks {
    private RootArtifactHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            hookFile(loader);
            hookFiles(loader);
            hookOs(loader);
            hookExec(loader);
            XposedBridge.log("ClaudeWilsonHook root-artifacts: installed");
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook root-artifacts: " + t);
        }
    }

    /** The per-path view both layers use; no package or target literal is involved. */
    static boolean hidden(String path) {
        return RootArtifactPolicy.hidden(path);
    }

    private static void hookFile(ClassLoader loader) {
        Class<?> file = XposedHelpers.findClass("java.io.File", loader);
        for (String method : new String[]{"exists", "isFile", "isDirectory", "canRead",
                "canExecute", "canWrite"}) {
            XposedBridge.hookAllMethods(file, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof File
                            && hidden(((File) param.thisObject).getPath())) {
                        param.setResult(false);
                    }
                }
            });
        }
        XposedBridge.hookAllMethods(file, "length", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof File
                        && hidden(((File) param.thisObject).getPath())) {
                    param.setResult(0L);
                }
            }
        });
        XposedBridge.hookAllMethods(file, "listFiles", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof File
                        && hidden(((File) param.thisObject).getPath())) {
                    param.setResult(null);
                }
            }
        });
    }

    private static void hookFiles(ClassLoader loader) {
        try {
            Class<?> files = XposedHelpers.findClass("java.nio.file.Files", loader);
            for (String method : new String[]{"exists", "isReadable", "isExecutable"}) {
                XposedBridge.hookAllMethods(files, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Path
                                && hidden(param.args[0].toString())) {
                            param.setResult(false);
                        }
                    }
                });
            }
            XposedBridge.hookAllMethods(files, "notExists", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Path
                            && hidden(param.args[0].toString())) {
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook root-artifacts Files: " + t);
        }
    }

    private static void hookOs(ClassLoader loader) {
        try {
            Class<?> os = XposedHelpers.findClass("android.system.Os", loader);
            XposedBridge.hookAllMethods(os, "access", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String
                            && hidden((String) param.args[0])) {
                        param.setThrowable(new ErrnoException("access", OsConstants.ENOENT));
                    }
                }
            });
            for (String method : new String[]{"stat", "lstat"}) {
                XposedBridge.hookAllMethods(os, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof String
                                && hidden((String) param.args[0])) {
                            param.setThrowable(new ErrnoException("stat", OsConstants.ENOENT));
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook root-artifacts Os: " + t);
        }
    }

    private static void hookExec(ClassLoader loader) {
        Class<?> runtime = XposedHelpers.findClass("java.lang.Runtime", loader);
        XposedBridge.hookAllMethods(runtime, "exec", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && RootArtifactPolicy.isSuCommand(param.args[0])) {
                    param.setThrowable(noSu());
                }
            }
        });
        Class<?> builder = XposedHelpers.findClass("java.lang.ProcessBuilder", loader);
        XposedBridge.hookAllMethods(builder, "start", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof ProcessBuilder
                        && RootArtifactPolicy.isSuCommand(
                                ((ProcessBuilder) param.thisObject).command())) {
                    param.setThrowable(noSu());
                }
            }
        });
    }

    private static IOException noSu() {
        return new IOException("Cannot run program \"su\": error=2, No such file or directory");
    }
}
