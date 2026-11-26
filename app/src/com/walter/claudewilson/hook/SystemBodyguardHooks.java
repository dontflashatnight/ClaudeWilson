package com.walter.claudewilson.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;

import com.walter.claudewilson.model.BodyguardPolicy;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * System-framework ({@code system_server}) anti-restart lock for Bodyguard {@code BLOCK_KILL}.
 *
 * <p>Scoped strictly to registered {@link TargetRegistry} packages — every other application on
 * the device passes through untouched in O(1). Works in three halves:
 * <ol>
 *   <li><b>Zero-window auto-lock</b>: hooks {@code ActivityManagerService.setProcessStateSummary}
 *       and immediately arms an in-memory lock the microsecond a registered target emits a
 *       {@code CWBG1|KILLED|} summary, closing the race window before {@code killProcess} finishes
 *       so FCM / WorkManager cannot silently respawn the target.</li>
 *   <li><b>Pre-window activity gate</b>: hooks {@code ActivityTaskManagerService.startActivity*}
 *       and returns {@code ActivityManager.START_ABORTED (102)} BEFORE any {@code Task},
 *       {@code ActivityRecord}, {@code Splash Screen}, or {@code ShellTransition} is created.</li>
 *   <li><b>Background process-spawn gate</b>: hooks {@code ProcessList.startProcessLocked} and
 *       returns {@code null}/{@code false} ONLY for non-activity background spawns (service,
 *       broadcast, content provider, job). Foreground activity launches are never short-circuited
 *       here, preventing Android 15+ orphaned Splash Screen freezes ({@code mCurrentFocus=null});
 *       if an activity bypasses the pre-window gate (e.g. Recents or implicit intent), the
 *       in-process {@code BodyguardHooks} boot lock kills it cleanly in {@code Application.attach}
 *       so {@code handleAppDiedLocked} tears down the starting window normally.</li>
 * </ol>
 */
final class SystemBodyguardHooks {
    private static final String LOCK_DIR = "/data/local/tmp/claudewilson/lock";
    private static final int START_ABORTED = 102;
    private static final byte[] KILLED_PREFIX =
            "CWBG1|KILLED|".getBytes(StandardCharsets.US_ASCII);
    private static final long CACHE_TTL_MS = 400L;

    private static final ConcurrentHashMap<String, Long> MEM_LOCKS =
            new ConcurrentHashMap<String, Long>();
    private static final ConcurrentHashMap<String, CachedLock> CACHE =
            new ConcurrentHashMap<String, CachedLock>();
    private static final ConcurrentHashMap<Class<?>, Method> HOSTING_TYPE_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();

    private static volatile boolean installed;

    private static final class CachedLock {
        final boolean locked;
        final long checkedAt;

        CachedLock(boolean locked, long checkedAt) {
            this.locked = locked;
            this.checkedAt = checkedAt;
        }
    }

    private SystemBodyguardHooks() {
    }

    static void install(ClassLoader loader) {
        if (installed) {
            return;
        }
        installed = true;
        // Pre-warm helper classes at system_server boot so an in-place APK upgrade cannot break
        // lazy class resolution before the user reboots.
        BodyguardPolicy.isLockEffective(0L, 0L, 0L);
        BodyguardPolicy.shouldBlockProcessStart("service");
        TargetRegistry.all();
        hookStateSummaryAutoLock(loader);
        hookActivityStartGate(loader);
        hookProcessStartGate(loader);
    }

    private static void hookActivityStartGate(ClassLoader loader) {
        try {
            Class<?> atms = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityTaskManagerService", loader);
            if (atms == null) {
                return;
            }
            XC_MethodHook gate = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.method instanceof Method)
                                || ((Method) param.method).getReturnType() != int.class) {
                            return;
                        }
                        Intent intent = findIntent(param.args);
                        if (intent == null) {
                            return;
                        }
                        String pkg = extractIntentPackage(intent);
                        if (pkg == null) {
                            return;
                        }
                        TargetSpec spec = TargetRegistry.byPackage(pkg);
                        if (spec == null || !spec.hooksEnabled()) {
                            return;
                        }
                        if (!isTargetLocked(spec.id())) {
                            return;
                        }
                        param.setResult(Integer.valueOf(START_ABORTED));
                    } catch (Throwable ignored) {
                    }
                }
            };
            String[] methods = {
                    "startActivity",
                    "startActivityAsUser",
                    "startActivityWithFeature",
            };
            for (String name : methods) {
                try {
                    XposedBridge.hookAllMethods(atms, name, gate);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hookStateSummaryAutoLock(ClassLoader loader) {
        try {
            Class<?> ams = XposedHelpers.findClassIfExists(
                    "com.android.server.am.ActivityManagerService", loader);
            if (ams == null) {
                return;
            }
            XposedBridge.hookAllMethods(ams, "setProcessStateSummary", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof byte[])) {
                            return;
                        }
                        byte[] state = (byte[]) param.args[0];
                        if (!startsWith(state, KILLED_PREFIX)) {
                            return;
                        }
                        int callingUid = Binder.getCallingUid();
                        TargetSpec spec = resolveTargetForUid(param.thisObject, callingUid);
                        if (spec == null) {
                            return;
                        }
                        long now = System.currentTimeMillis();
                        MEM_LOCKS.put(spec.id(), Long.valueOf(now));
                        CACHE.remove(spec.id());
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hookProcessStartGate(ClassLoader loader) {
        try {
            Class<?> pl = XposedHelpers.findClassIfExists(
                    "com.android.server.am.ProcessList", loader);
            if (pl == null) {
                return;
            }
            XposedBridge.hookAllMethods(pl, "startProcessLocked", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        ApplicationInfo appInfo = findAppInfo(param.args);
                        if (appInfo == null || appInfo.packageName == null) {
                            return;
                        }
                        TargetSpec spec = TargetRegistry.byPackage(appInfo.packageName);
                        if (spec == null || !spec.hooksEnabled()) {
                            return;
                        }
                        if (!isTargetLocked(spec.id())) {
                            return;
                        }
                        // Never short-circuit foreground activity launches here: Android 15+
                        // WindowManager has already created the ActivityRecord & fullscreen
                        // Splash Screen before calling startProcessLocked. Returning null/false
                        // strands the Splash Screen with mCurrentFocus=null and freezes UI.
                        String hostingType = findHostingType(param.args);
                        if (!BodyguardPolicy.shouldBlockProcessStart(hostingType)) {
                            return;
                        }
                        if (param.method instanceof Method) {
                            Class<?> ret = ((Method) param.method).getReturnType();
                            if (ret == boolean.class) {
                                param.setResult(Boolean.FALSE);
                            } else {
                                param.setResult(null);
                            }
                        } else {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean isTargetLocked(String targetId) {
        long now = System.currentTimeMillis();
        CachedLock cached = CACHE.get(targetId);
        if (cached != null && (now - cached.checkedAt) < CACHE_TTL_MS) {
            return cached.locked;
        }
        Long memObj = MEM_LOCKS.get(targetId);
        long memLock = memObj == null ? 0L : memObj.longValue();
        long lockFileTs = readTimestampFile(new File(LOCK_DIR, targetId + ".lock"));
        long unlockFileTs = readTimestampFile(new File(LOCK_DIR, targetId + ".unlock"));
        boolean locked = BodyguardPolicy.isLockEffective(memLock, lockFileTs, unlockFileTs);
        if (!locked && memLock > 0L && unlockFileTs >= memLock) {
            MEM_LOCKS.remove(targetId);
        }
        CACHE.put(targetId, new CachedLock(locked, now));
        return locked;
    }

    private static long readTimestampFile(File file) {
        if (!file.isFile()) {
            return 0L;
        }
        long mtime = file.lastModified();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            if (n > 0) {
                String text = new String(buf, 0, n, StandardCharsets.US_ASCII).trim();
                if (!text.isEmpty()) {
                    long parsed = Long.parseLong(text);
                    if (parsed > mtime) {
                        return parsed;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return mtime;
    }

    private static TargetSpec resolveTargetForUid(Object amsObj, int uid) {
        if (amsObj == null || uid <= 1000) {
            return null;
        }
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(amsObj, "mContext");
            if (ctx == null) {
                return null;
            }
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) {
                return null;
            }
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null) {
                return null;
            }
            for (String pkg : pkgs) {
                TargetSpec spec = TargetRegistry.byPackage(pkg);
                if (spec != null && spec.hooksEnabled()) {
                    return spec;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ApplicationInfo findAppInfo(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof ApplicationInfo) {
                return (ApplicationInfo) arg;
            }
        }
        return null;
    }

    private static Intent findIntent(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    private static String extractIntentPackage(Intent intent) {
        if (intent == null) {
            return null;
        }
        ComponentName cmp = intent.getComponent();
        if (cmp != null) {
            String pkg = cmp.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                return pkg;
            }
        }
        String pkg = intent.getPackage();
        return (pkg != null && !pkg.isEmpty()) ? pkg : null;
    }

    private static String findHostingType(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            Class<?> cls = arg.getClass();
            if ("HostingRecord".equals(cls.getSimpleName())) {
                try {
                    Method m = HOSTING_TYPE_METHODS.get(cls);
                    if (m == null) {
                        m = cls.getMethod("getType");
                        m.setAccessible(true);
                        HOSTING_TYPE_METHODS.put(cls, m);
                    }
                    Object res = m.invoke(arg);
                    if (res instanceof String) {
                        return (String) res;
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
