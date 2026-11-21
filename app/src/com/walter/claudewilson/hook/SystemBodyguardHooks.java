package com.walter.claudewilson.hook;

import android.content.Context;
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
 * the device passes through untouched in O(1). Works in two halves:
 * <ol>
 *   <li><b>Zero-window auto-lock</b>: hooks {@code ActivityManagerService.setProcessStateSummary}
 *       and immediately arms an in-memory lock the microsecond a registered target emits a
 *       {@code CWBG1|KILLED|} summary, closing the race window before {@code killProcess} finishes
 *       so FCM / WorkManager cannot silently respawn the target.</li>
 *   <li><b>Process-spawn gate</b>: hooks {@code ProcessList.startProcessLocked} and returns
 *       {@code null}/{@code false} while the target is locked (in-memory or via persisted
 *       {@code /data/local/tmp/claudewilson/lock/<target>.lock}), until superseded by a newer
 *       {@code <target>.unlock} written when the user taps Unlock in ClaudeWilson.</li>
 * </ol>
 */
final class SystemBodyguardHooks {
    private static final String LOCK_DIR = "/data/local/tmp/claudewilson/lock";
    private static final byte[] KILLED_PREFIX =
            "CWBG1|KILLED|".getBytes(StandardCharsets.US_ASCII);
    private static final long CACHE_TTL_MS = 400L;

    private static final ConcurrentHashMap<String, Long> MEM_LOCKS =
            new ConcurrentHashMap<String, Long>();
    private static final ConcurrentHashMap<String, CachedLock> CACHE =
            new ConcurrentHashMap<String, CachedLock>();

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
        hookStateSummaryAutoLock(loader);
        hookProcessStartGate(loader);
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
