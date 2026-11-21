package com.walter.claudewilson.hook;

import android.system.ErrnoException;
import android.system.OsConstants;

import com.walter.claudewilson.model.AppVisibilityPolicy;
import com.walter.claudewilson.model.RootArtifactPolicy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private static volatile HookConfig visConfig;
    private static volatile String visTargetPkg = "";

    private RootArtifactHooks() {
    }

    static void install(ClassLoader loader) {
        install(loader, null, "");
    }

    static void install(ClassLoader loader, HookConfig config, String targetPackage) {
        visConfig = config;
        visTargetPkg = targetPackage == null ? "" : targetPackage;
        try {
            hookFile(loader);
            hookFileInputStream(loader);
            hookFiles(loader);
            hookOs(loader);
            hookExec(loader);
            if (config == null || config.appVisHideDev) {
                hookDebugAndAdb(loader);
            }
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook root-artifacts: installed");
            }
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook root-artifacts: " + t);
            }
        }
    }

    /**
     * Evaluates whether a path must be hidden as either a root artifact or a hidden-app
     * filesystem side-channel ({@code /data/data/<pkg>}, {@code /sdcard/Android/data/<pkg>},
     * {@code /data/app/.../<pkg>-...}).
     */
    static boolean hidden(String path) {
        if (RootArtifactPolicy.hidden(path)) {
            return true;
        }
        HookConfig cfg = visConfig;
        if (cfg != null && cfg.appVisStrict) {
            String pkg = AppVisibilityPolicy.extractPackageFromPath(path);
            if (pkg != null) {
                boolean isSysHeuristic = cfg.appVisExcludeSystem
                        && (pkg.startsWith("com.android.") || pkg.startsWith("android.")
                        || pkg.startsWith("com.google.android."));
                return AppVisibilityPolicy.isHidden(
                        cfg.appVisMode, cfg.appVisPackages, cfg.appVisExtraHidden,
                        cfg.appVisExcludeSystem, pkg, isSysHeuristic, visTargetPkg);
            }
        }
        return false;
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
        XposedBridge.hookAllMethods(file, "list", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof File)) {
                    return;
                }
                File dir = (File) param.thisObject;
                if (hidden(dir.getPath())) {
                    param.setResult(null);
                    return;
                }
                Object res = param.getResult();
                if (!(res instanceof String[])) {
                    return;
                }
                String[] names = (String[]) res;
                List<String> kept = null;
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    if (name != null && hidden(new File(dir, name).getPath())) {
                        if (kept == null) {
                            kept = new ArrayList<String>(names.length);
                            for (int j = 0; j < i; j++) {
                                kept.add(names[j]);
                            }
                        }
                    } else if (kept != null) {
                        kept.add(name);
                    }
                }
                if (kept != null) {
                    param.setResult(kept.toArray(new String[0]));
                }
            }
        });
        XposedBridge.hookAllMethods(file, "listFiles", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof File)) {
                    return;
                }
                File dir = (File) param.thisObject;
                if (hidden(dir.getPath())) {
                    param.setResult(null);
                    return;
                }
                Object res = param.getResult();
                if (!(res instanceof File[])) {
                    return;
                }
                File[] children = (File[]) res;
                List<File> kept = null;
                for (int i = 0; i < children.length; i++) {
                    File child = children[i];
                    if (child != null && hidden(child.getPath())) {
                        if (kept == null) {
                            kept = new ArrayList<File>(children.length);
                            for (int j = 0; j < i; j++) {
                                kept.add(children[j]);
                            }
                        }
                    } else if (kept != null) {
                        kept.add(child);
                    }
                }
                if (kept != null) {
                    param.setResult(kept.toArray(new File[0]));
                }
            }
        });
    }

    private static void hookFileInputStream(ClassLoader loader) {
        try {
            Class<?> fis = XposedHelpers.findClass("java.io.FileInputStream", loader);
            XposedBridge.hookAllConstructors(fis, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    String path = null;
                    if (param.args[0] instanceof String) {
                        path = (String) param.args[0];
                    } else if (param.args[0] instanceof File) {
                        path = ((File) param.args[0]).getPath();
                    }
                    if (path != null && hidden(path)) {
                        param.setThrowable(new FileNotFoundException(path + ": open failed: ENOENT (No such file or directory)"));
                    }
                }
            });
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook root-artifacts FileInputStream: " + t);
            }
        }
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
            for (String method : new String[]{"stat", "lstat", "open"}) {
                XposedBridge.hookAllMethods(os, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof String
                                && hidden((String) param.args[0])) {
                            param.setThrowable(new ErrnoException(method, OsConstants.ENOENT));
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
        XposedBridge.hookAllMethods(runtime, "exec",
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) {
                    return;
                }
                if (RootArtifactPolicy.isSuCommand(param.args[0])) {
                    param.setThrowable(noSu());
                    return;
                }
                String gpKey = RootArtifactPolicy.parseGetpropCommand(param.args[0]);
                if (gpKey != null) {
                    param.setResult(fakeGetpropProcess(loader, visConfig, gpKey));
                    return;
                }
                if (RootArtifactPolicy.isMountCommand(param.args[0])) {
                    param.setResult(fakeMountProcess());
                    return;
                }
                HookConfig cfg = visConfig;
                if (cfg != null && cfg.appVisStrict && AppVisibilityPolicy.isHiddenPmCommand(
                        param.args[0], cfg.appVisMode, cfg.appVisPackages,
                        cfg.appVisExtraHidden, cfg.appVisExcludeSystem, visTargetPkg)) {
                    param.setThrowable(noPm());
                }
            }
        });
        Class<?> builder = XposedHelpers.findClass("java.lang.ProcessBuilder", loader);
        XposedBridge.hookAllMethods(builder, "start",
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof ProcessBuilder)) {
                    return;
                }
                List<String> cmd = ((ProcessBuilder) param.thisObject).command();
                if (RootArtifactPolicy.isSuCommand(cmd)) {
                    param.setThrowable(noSu());
                    return;
                }
                String gpKey = RootArtifactPolicy.parseGetpropCommand(cmd);
                if (gpKey != null) {
                    param.setResult(fakeGetpropProcess(loader, visConfig, gpKey));
                    return;
                }
                if (RootArtifactPolicy.isMountCommand(cmd)) {
                    param.setResult(fakeMountProcess());
                    return;
                }
                HookConfig cfg = visConfig;
                if (cfg != null && cfg.appVisStrict && AppVisibilityPolicy.isHiddenPmCommand(
                        cmd, cfg.appVisMode, cfg.appVisPackages,
                        cfg.appVisExtraHidden, cfg.appVisExcludeSystem, visTargetPkg)) {
                    param.setThrowable(noPm());
                }
            }
        });
    }

    /**
     * Serves {@code getprop} subprocess calls in-process from our spoofed property map so target
     * code cannot bypass Java/native SystemProperties hooks by forking {@code /system/bin/getprop}.
     */
    private static Process fakeGetpropProcess(ClassLoader loader, HookConfig config, String key) {
        String output;
        if (key == null || key.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (config != null) {
                for (java.util.Map.Entry<String, String> e
                        : SystemPropertiesHooks.resolvedMap(config).entrySet()) {
                    sb.append('[').append(e.getKey()).append("]: [")
                            .append(e.getValue() == null ? "" : e.getValue()).append("]\n");
                }
            }
            sb.append("[ro.debuggable]: [0]\n");
            sb.append("[ro.secure]: [1]\n");
            sb.append("[ro.boot.verifiedbootstate]: [green]\n");
            sb.append("[ro.boot.flash.locked]: [1]\n");
            sb.append("[ro.boot.vbmeta.device_state]: [locked]\n");
            output = sb.toString();
        } else {
            String val = cleanSecurityProp(key);
            if (val == null && config != null) {
                val = SystemPropertiesHooks.resolve(config, key);
            }
            if (val == null) {
                try {
                    Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", loader);
                    Object raw = XposedHelpers.callStaticMethod(sp, "get", key, "");
                    val = raw == null ? "" : String.valueOf(raw);
                } catch (Throwable ignored) {
                    val = "";
                }
            }
            output = val + "\n";
        }
        return new FakeProcess(output);
    }

    /** Serves bare {@code mount} in-process with a clean read-only stock partition table. */
    private static Process fakeMountProcess() {
        return new FakeProcess(
                "/dev/block/dm-0 on / type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-1 on /system type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-2 on /system_ext type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-3 on /vendor type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-4 on /product type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-5 on /odm type erofs (ro,seclabel,relatime)\n"
                + "/dev/block/dm-6 on /data type f2fs (rw,seclabel,nosuid,nodev,noatime)\n");
    }

    private static String cleanSecurityProp(String key) {
        if ("ro.debuggable".equals(key)) return "0";
        if ("ro.secure".equals(key)) return "1";
        if ("ro.boot.verifiedbootstate".equals(key)) return "green";
        if ("ro.boot.flash.locked".equals(key)) return "1";
        if ("ro.boot.vbmeta.device_state".equals(key)) return "locked";
        if ("service.adb.root".equals(key)) return "";
        return null;
    }

    private static final class FakeProcess extends Process {
        private final byte[] stdoutBytes;

        FakeProcess(String stdout) {
            this.stdoutBytes = stdout == null
                    ? new byte[0] : stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public java.io.OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(stdoutBytes);
        }

        @Override public java.io.InputStream getErrorStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override public int waitFor() {
            return 0;
        }

        @Override public int exitValue() {
            return 0;
        }

        @Override public void destroy() {
        }
    }

    private static IOException noSu() {
        return new IOException("Cannot run program \"su\": error=2, No such file or directory");
    }

    private static IOException noPm() {
        return new IOException("Cannot run program \"pm\": error=13, Permission denied");
    }

    /** Hides USB/wireless debugging toggles and debugger state from target checks. */
    private static void hookDebugAndAdb(ClassLoader loader) {
        try {
            Class<?> debug = XposedHelpers.findClass("android.os.Debug", loader);
            XC_MethodHook alwaysFalse = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            };
            XposedBridge.hookAllMethods(debug, "isDebuggerConnected", alwaysFalse);
            XposedBridge.hookAllMethods(debug, "waitingForDebugger", alwaysFalse);
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook root-artifacts Debug: " + t);
            }
        }
        try {
            Class<?> global = XposedHelpers.findClass("android.provider.Settings$Global", loader);
            XC_MethodHook zeroInt = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isAdbSettingKey(param.args)) {
                        param.setResult(0);
                    }
                }
            };
            XC_MethodHook zeroLong = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isAdbSettingKey(param.args)) {
                        param.setResult(0L);
                    }
                }
            };
            XC_MethodHook zeroString = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isAdbSettingKey(param.args)) {
                        param.setResult("0");
                    }
                }
            };
            XposedBridge.hookAllMethods(global, "getInt", zeroInt);
            XposedBridge.hookAllMethods(global, "getIntForUser", zeroInt);
            XposedBridge.hookAllMethods(global, "getLong", zeroLong);
            XposedBridge.hookAllMethods(global, "getLongForUser", zeroLong);
            XposedBridge.hookAllMethods(global, "getString", zeroString);
            XposedBridge.hookAllMethods(global, "getStringForUser", zeroString);
        } catch (Throwable t) {
            if (HookEntry.debugLog) {
                XposedBridge.log("ClaudeWilsonHook root-artifacts Settings.Global: " + t);
            }
        }
    }

    private static boolean isAdbSettingKey(Object[] args) {
        if (args == null || args.length < 2 || args[1] == null) {
            return false;
        }
        String key = String.valueOf(args[1]);
        return "adb_enabled".equals(key)
                || "development_settings_enabled".equals(key)
                || "adb_wifi_enabled".equals(key);
    }
}
