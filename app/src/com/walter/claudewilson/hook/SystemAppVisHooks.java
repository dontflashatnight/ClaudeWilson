package com.walter.claudewilson.hook;

import android.os.SystemClock;

import com.walter.claudewilson.model.AppVisibilityPolicy;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HMA-grade {@code system_server} package visibility filter (Android 13–17, API 33–37).
 *
 * <p>Hooks {@code com.android.server.pm.AppsFilterBase.shouldFilterApplication} — the single OS
 * choke point shared by {@code PackageManager}, direct Binder {@code IPackageManager} calls,
 * {@code LauncherApps}, {@code PackageInstaller}, {@code Intent} resolution, and
 * {@code canPackageQuery}.
 *
 * <p>Strictly isolated to registered {@link TargetRegistry} callers:
 * <ul>
 *   <li>All system UIDs ({@code appId < 10000}) and all non-registered apps exit in O(1).</li>
 *   <li>Runs in {@code afterHookedMethod} and only strengthens visibility filtering
 *       ({@code false -> true}), never un-hiding packages Android itself already filtered.</li>
 *   <li>Hot-reloads per-target persona configs via {@code mtime} at most once per second so
 *       whitelist/blacklist edits apply immediately without rebooting {@code system_server}.</li>
 * </ul>
 */
final class SystemAppVisHooks {
    private static final long RELOAD_INTERVAL_MS = 1000L;
    private static final Method NO_METHOD;

    static {
        Method sentinel = null;
        try {
            sentinel = Object.class.getMethod("toString");
        } catch (Throwable ignored) {
        }
        NO_METHOD = sentinel;
    }

    private static final Map<String, CachedPolicy> POLICY_CACHE =
            new ConcurrentHashMap<String, CachedPolicy>();
    private static final Map<Integer, String> TARGET_UID_CACHE =
            new ConcurrentHashMap<Integer, String>();
    private static final Map<Class<?>, Method> PKG_NAME_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> IS_SYS_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> GET_PKGS_FOR_UID_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();

    private static volatile boolean installed;

    private SystemAppVisHooks() {
    }

    static void install(ClassLoader loader) {
        synchronized (SystemAppVisHooks.class) {
            if (installed) {
                return;
            }
            installed = true;
        }
        try {
            Class<?> appsFilter = XposedHelpers.findClassIfExists(
                    "com.android.server.pm.AppsFilterBase", loader);
            if (appsFilter == null) {
                appsFilter = XposedHelpers.findClassIfExists(
                        "com.android.server.pm.AppsFilterImpl", loader);
            }
            if (appsFilter == null) {
                return;
            }
            XposedBridge.hookAllMethods(appsFilter, "shouldFilterApplication", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // Only strengthen filtering: if Android already hid it, nothing to do.
                        if (!Boolean.FALSE.equals(param.getResult())) {
                            return;
                        }
                        Object[] args = param.args;
                        if (args == null || args.length < 4 || !(args[1] instanceof Integer)) {
                            return;
                        }
                        int callingUid = ((Integer) args[1]).intValue();
                        if (callingUid < 0 || (callingUid % 100000) < 10000) {
                            return;
                        }
                        String callingPkg = resolveCallingTarget(args[0], callingUid, args[2]);
                        if (callingPkg == null) {
                            return;
                        }
                        Object targetSetting = args[3];
                        if (targetSetting == null) {
                            return;
                        }
                        String targetPkg = extractPackageName(targetSetting);
                        if (targetPkg == null || targetPkg.isEmpty() || callingPkg.equals(targetPkg)) {
                            return;
                        }
                        Policy policy = policyFor(callingPkg);
                        if (policy == null) {
                            return;
                        }
                        boolean targetIsSystem = extractIsSystem(targetSetting);
                        if (AppVisibilityPolicy.isHidden(policy.mode, policy.packages,
                                policy.extraHidden, policy.excludeSystemApps,
                                targetPkg, targetIsSystem, callingPkg)) {
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                        // Never allow any exception to disrupt system_server PMS queries.
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String resolveCallingTarget(Object computer, int callingUid, Object callingSetting) {
        if (callingSetting != null) {
            String direct = extractPackageName(callingSetting);
            if (direct != null && !direct.isEmpty()) {
                TargetSpec spec = TargetRegistry.byPackage(direct);
                if (spec != null && spec.hooksEnabled()) {
                    TARGET_UID_CACHE.put(callingUid, spec.packageName());
                    return spec.packageName();
                }
                return null;
            }
        }
        String cached = TARGET_UID_CACHE.get(callingUid);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        if (computer != null) {
            String[] pkgs = getPackagesForUid(computer, callingUid);
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    TargetSpec spec = TargetRegistry.byPackage(pkg);
                    if (spec != null && spec.hooksEnabled()) {
                        TARGET_UID_CACHE.put(callingUid, spec.packageName());
                        return spec.packageName();
                    }
                }
                TARGET_UID_CACHE.put(callingUid, "");
            }
        }
        return null;
    }

    private static String extractPackageName(Object pkgState) {
        if (pkgState == null) {
            return null;
        }
        Class<?> cls = pkgState.getClass();
        Method m = PKG_NAME_METHODS.get(cls);
        if (m == null) {
            m = findNoArgMethod(cls, "getPackageName");
            PKG_NAME_METHODS.put(cls, m != null ? m : NO_METHOD);
        }
        if (m != null && m != NO_METHOD) {
            try {
                Object v = m.invoke(pkgState);
                return v instanceof String ? (String) v : null;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean extractIsSystem(Object pkgState) {
        if (pkgState == null) {
            return false;
        }
        Class<?> cls = pkgState.getClass();
        Method m = IS_SYS_METHODS.get(cls);
        if (m == null) {
            m = findNoArgMethod(cls, "isSystem");
            IS_SYS_METHODS.put(cls, m != null ? m : NO_METHOD);
        }
        if (m != null && m != NO_METHOD) {
            try {
                Object v = m.invoke(pkgState);
                if (v instanceof Boolean) {
                    return ((Boolean) v).booleanValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String[] getPackagesForUid(Object computer, int uid) {
        Class<?> cls = computer.getClass();
        Method m = GET_PKGS_FOR_UID_METHODS.get(cls);
        if (m == null) {
            try {
                m = cls.getMethod("getPackagesForUid", int.class);
                m.setAccessible(true);
            } catch (Throwable ignored) {
                m = NO_METHOD;
            }
            GET_PKGS_FOR_UID_METHODS.put(cls, m);
        }
        if (m != null && m != NO_METHOD) {
            try {
                Object res = m.invoke(computer, uid);
                if (res instanceof String[]) {
                    return (String[]) res;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Policy policyFor(String pkg) {
        TargetSpec spec = TargetRegistry.byPackage(pkg);
        if (spec == null || !spec.hooksEnabled()) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        CachedPolicy entry = POLICY_CACHE.get(pkg);
        if (entry != null && (now - entry.checkedAtMs) < RELOAD_INTERVAL_MS) {
            return entry.policy;
        }
        File file = new File(spec.configPath());
        long mtime = file.isFile() ? file.lastModified() : -1L;
        if (mtime < 0L && !spec.legacyConfigPath().isEmpty()) {
            File legacy = new File(spec.legacyConfigPath());
            if (legacy.isFile()) {
                file = legacy;
                mtime = legacy.lastModified();
            }
        }
        if (entry != null && entry.mtime == mtime && entry.path.equals(file.getPath())) {
            entry.checkedAtMs = now;
            return entry.policy;
        }
        Policy loaded = null;
        if (mtime >= 0L) {
            HookConfig cfg = HookConfig.load(spec);
            if (cfg != null) {
                loaded = new Policy(cfg.appVisMode, cfg.appVisPackages,
                        cfg.appVisExtraHidden, cfg.appVisExcludeSystem);
            }
        }
        POLICY_CACHE.put(pkg, new CachedPolicy(file.getPath(), mtime, now, loaded));
        return loaded;
    }

    private static final class CachedPolicy {
        final String path;
        final long mtime;
        volatile long checkedAtMs;
        final Policy policy;

        CachedPolicy(String path, long mtime, long checkedAtMs, Policy policy) {
            this.path = path;
            this.mtime = mtime;
            this.checkedAtMs = checkedAtMs;
            this.policy = policy;
        }
    }

    private static final class Policy {
        final String mode;
        final Set<String> packages;
        final Set<String> extraHidden;
        final boolean excludeSystemApps;

        Policy(String mode, Set<String> packages, Set<String> extraHidden,
               boolean excludeSystemApps) {
            this.mode = mode;
            this.packages = packages;
            this.extraHidden = extraHidden;
            this.excludeSystemApps = excludeSystemApps;
        }
    }
}
