package com.walter.claudewilson.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import com.walter.claudewilson.model.LocationFix;
import com.walter.claudewilson.model.LocationPlan;
import com.walter.claudewilson.model.TargetRegistry;
import com.walter.claudewilson.model.TargetSpec;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Strictly per-target {@code system_server} location defense-in-depth (Android 13–17, API 33–37).
 *
 * <p>Activated only when the user enables {@code android} (System Framework) in LSPosed/Vector
 * scope. Inspired by VirtualRegion 2.0.5's {@code LocationManagerService} &amp;
 * {@code LocationProviderManager$*Registration.acceptLocationChange} design, but strictly isolated:
 *
 * <ul>
 *   <li>Every non-registered app passes through untouched in nanoseconds (Maps/navigation/system
 *       services are never altered).</li>
 *   <li>Target persona JSON files are hot-reloaded via {@code mtime} at most once per second so
 *       region switches take effect without rebooting {@code system_server}.</li>
 *   <li>Permission boundaries are respected: unauthorized targets never receive fabricated fixes,
 *       and coarse-only targets receive 3 km quantized coordinates without altitude/speed/bearing.</li>
 *   <li>Raw GNSS status/NMEA/measurements/batching registrations from active targets are blocked
 *       so satellite data cannot leak real GPS from underneath upper-layer hooks.</li>
 * </ul>
 */
final class SystemLocationHooks {
    private static final long RELOAD_INTERVAL_MS = 1000L;
    private static final String FINE_PERM = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE_PERM = "android.permission.ACCESS_COARSE_LOCATION";

    private static final Map<String, CachedPolicy> CACHE =
            new ConcurrentHashMap<String, CachedPolicy>();
    private static volatile boolean installed;

    private SystemLocationHooks() {
    }

    static void install(ClassLoader loader) {
        synchronized (SystemLocationHooks.class) {
            if (installed) {
                return;
            }
            installed = true;
        }
        try {
            hookLocationManagerService(loader);
            hookProviderRegistrations(loader);
        } catch (Throwable ignored) {
            // Never allow any exception to disrupt system_server startup.
        }
    }

    private static void hookLocationManagerService(ClassLoader loader) {
        Class<?> lms = XposedHelpers.findClassIfExists(
                "com.android.server.location.LocationManagerService", loader);
        if (lms == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(lms, "getLastLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) {
                        return;
                    }
                    String pkg = findTargetPackageArg(param.args);
                    if (pkg == null) {
                        return;
                    }
                    Policy policy = policyFor(pkg);
                    if (policy == null) {
                        return;
                    }
                    Object res = param.getResult();
                    int permMode = resolvePermissionMode(param.thisObject, pkg, res);
                    if (permMode == 0) {
                        return;
                    }
                    String provider = (param.args != null && param.args.length > 0
                            && param.args[0] instanceof String)
                            ? (String) param.args[0] : "gps";
                    param.setResult(policy.buildLocation(permMode == 1, provider));
                }
            });
        } catch (Throwable ignored) {
        }

        // Prevent direct GNSS satellite / NMEA / measurement / batching leaks for active targets.
        String[] gnssMethods = {
                "registerGnssStatusCallback",
                "registerGnssNmeaCallback",
                "addGnssMeasurementsListener",
                "addGnssNavigationMessageListener",
                "addGnssAntennaInfoListener",
                "startGnssBatch",
                "addGnssBatchingCallback",
        };
        XC_MethodHook blockGnss = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String pkg = findTargetPackageArg(param.args);
                if (pkg == null || policyFor(pkg) == null) {
                    return;
                }
                Class<?> ret = param.method instanceof Method
                        ? ((Method) param.method).getReturnType() : void.class;
                if (ret == boolean.class || ret == Boolean.class) {
                    param.setResult(false);
                } else if (ret == int.class || ret == Integer.class) {
                    param.setResult(0);
                } else {
                    param.setResult(null);
                }
            }
        };
        for (String name : gnssMethods) {
            try {
                XposedBridge.hookAllMethods(lms, name, blockGnss);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookProviderRegistrations(final ClassLoader loader) {
        final Class<?> locationResultClass = XposedHelpers.findClassIfExists(
                "android.location.LocationResult", loader);
        if (locationResultClass == null) {
            return;
        }
        String[] regClasses = {
                "com.android.server.location.provider.LocationProviderManager$LocationRegistration",
                "com.android.server.location.provider.LocationProviderManager$LocationListenerRegistration",
                "com.android.server.location.provider.LocationProviderManager$LocationPendingIntentRegistration",
                "com.android.server.location.provider.LocationProviderManager$GetCurrentLocationListenerRegistration",
        };
        XC_MethodHook rewriteDelivery = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject == null || param.args == null || param.args.length == 0
                        || param.args[0] == null) {
                    return;
                }
                String pkg = identityPackage(param.thisObject);
                if (pkg == null) {
                    return;
                }
                Policy policy = policyFor(pkg);
                if (policy == null) {
                    return;
                }
                int permLevel = registrationPermissionLevel(param.thisObject);
                if (permLevel <= 0) {
                    return;
                }
                String provider = extractProvider(param.args[0]);
                Location fake = policy.buildLocation(permLevel == 1, provider);
                try {
                    Object wrapped = XposedHelpers.callStaticMethod(
                            locationResultClass, "create", new Object[]{new Location[]{fake}});
                    if (wrapped != null) {
                        param.args[0] = wrapped;
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        for (String clsName : regClasses) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(clsName, loader);
                if (cls != null) {
                    XposedBridge.hookAllMethods(cls, "acceptLocationChange", rewriteDelivery);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String findTargetPackageArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                TargetSpec spec = TargetRegistry.byPackage((String) arg);
                if (spec != null && spec.hooksEnabled()) {
                    return spec.packageName();
                }
            }
        }
        return null;
    }

    private static String identityPackage(Object registration) {
        try {
            Object identity = XposedHelpers.callMethod(registration, "getIdentity");
            if (identity == null) {
                return null;
            }
            Object pkgObj = XposedHelpers.callMethod(identity, "getPackageName");
            if (pkgObj instanceof String) {
                TargetSpec spec = TargetRegistry.byPackage((String) pkgObj);
                if (spec != null && spec.hooksEnabled()) {
                    return spec.packageName();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int registrationPermissionLevel(Object registration) {
        try {
            Object val = XposedHelpers.callMethod(registration, "getPermissionLevel");
            if (val instanceof Number) {
                int level = ((Number) val).intValue();
                // AOSP LocationPermissions: 0=NONE, 1=COARSE, 2=FINE
                if (level == 1 || level == 2) {
                    return level;
                }
                return 0;
            }
        } catch (Throwable ignored) {
        }
        return 2;
    }

    private static int resolvePermissionMode(Object lmsInstance, String pkg, Object result) {
        try {
            Object ctxObj = XposedHelpers.getObjectField(lmsInstance, "mContext");
            if (ctxObj instanceof Context) {
                PackageManager pm = ((Context) ctxObj).getPackageManager();
                if (pm != null) {
                    if (pm.checkPermission(FINE_PERM, pkg) == PackageManager.PERMISSION_GRANTED) {
                        return 2;
                    }
                    if (pm.checkPermission(COARSE_PERM, pkg) == PackageManager.PERMISSION_GRANTED) {
                        return 1;
                    }
                    return 0;
                }
            }
        } catch (Throwable ignored) {
        }
        if (result instanceof Location) {
            Location real = (Location) result;
            return (real.getAccuracy() >= 1000f && !real.hasAltitude()) ? 1 : 2;
        }
        return 0;
    }

    private static String extractProvider(Object locationResult) {
        try {
            Object last = XposedHelpers.callMethod(locationResult, "getLastLocation");
            if (last instanceof Location) {
                String p = ((Location) last).getProvider();
                if (p != null && !p.isEmpty()) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return "gps";
    }

    private static Policy policyFor(String pkg) {
        TargetSpec spec = TargetRegistry.byPackage(pkg);
        if (spec == null || !spec.hooksEnabled()) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        CachedPolicy entry = CACHE.get(pkg);
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
            if (cfg != null && cfg.hasLocation()) {
                loaded = new Policy(
                        new LocationPlan(cfg.latitude, cfg.longitude, cfg.locationSeed, false),
                        new LocationPlan(cfg.latitude, cfg.longitude, cfg.locationSeed, true));
            }
        }
        CACHE.put(pkg, new CachedPolicy(file.getPath(), mtime, now, loaded));
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
        final LocationPlan fine;
        final LocationPlan coarse;

        Policy(LocationPlan fine, LocationPlan coarse) {
            this.fine = fine;
            this.coarse = coarse;
        }

        Location buildLocation(boolean isCoarse, String provider) {
            LocationPlan plan = isCoarse ? coarse : fine;
            long now = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtimeNanos();
            LocationFix fix = plan.fix(now, elapsed);
            Location loc = new Location(provider == null || provider.isEmpty() ? "gps" : provider);
            loc.setLatitude(fix.latitude);
            loc.setLongitude(fix.longitude);
            loc.setAccuracy(fix.accuracy);
            loc.setTime(fix.timeMillis);
            loc.setElapsedRealtimeNanos(fix.elapsedRealtimeNanos);
            if (fix.hasAltitude) {
                loc.setAltitude(fix.altitude);
                loc.setVerticalAccuracyMeters(fix.verticalAccuracyMeters);
            }
            if (fix.hasSpeed) {
                loc.setSpeed(fix.speed);
                loc.setSpeedAccuracyMetersPerSecond(fix.speedAccuracyMetersPerSecond);
            }
            if (fix.hasBearing) {
                loc.setBearing(fix.bearing);
                loc.setBearingAccuracyDegrees(fix.bearingAccuracyDegrees);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                loc.setMock(false);
            }
            Bundle extras = new Bundle();
            extras.putInt("satellites", fix.satellites);
            loc.setExtras(extras);
            return loc;
        }
    }
}
