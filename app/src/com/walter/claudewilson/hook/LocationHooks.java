package com.walter.claudewilson.hook;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.walter.claudewilson.model.LocationFix;
import com.walter.claudewilson.model.LocationPlan;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Per-target location simulation, ported from VirtualRegion 2.0.5.
 *
 * <p>VirtualRegion hooks {@code system_server}'s {@code LocationManagerService} and fabricates a
 * fix for every registered listener, PendingIntent and current-location request. A Zygisk module
 * scoped to one app cannot reach system_server, so this port intercepts the same public surface
 * in the target process: {@code LocationManager} plus the Play Services fused client. The payload
 * is still a plain region point; nothing here reads a real coordinate or the device's own
 * location.
 *
 * <p>Permission gate matches {@code F3.f#c}: no location permission means pass-through, fine
 * means the exact point, coarse means the same point snapped to a 3 km grid with accuracy
 * raised to 3 km and altitude/speed/bearing removed ({@code G3.u0#H}).
 */
final class LocationHooks {
    static volatile String status = "not-run";

    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private static final long MIN_INTERVAL_MS = 1000L;

    private static final Map<Object, Registration> REGISTRATIONS =
            Collections.synchronizedMap(new IdentityHashMap<Object, Registration>());

    private static volatile Context appContext;
    private static volatile LocationPlan finePlan;
    private static volatile LocationPlan coarsePlan;
    private static volatile ClassLoader classLoader;
    private static volatile boolean fusedHooked;
    private static final java.util.concurrent.atomic.AtomicInteger served =
            new java.util.concurrent.atomic.AtomicInteger();

    private LocationHooks() {
    }

    /** Self-check runs after Application.attach, so the permission gate can be resolved then. */
    static void refreshStatus() {
        int mode = permissionMode();
        if (status.startsWith("pending")) {
            status = mode == 2 ? "fine" : mode == 1 ? "coarse" : "pending " + diagnostic();
        }
    }

    static int served() {
        return served.get();
    }

    private static String diagnostic() {
        Context context = context();
        if (context == null) {
            return "ctx=null";
        }
        return "fine=" + context.checkSelfPermission(FINE)
                + " coarse=" + context.checkSelfPermission(COARSE)
                + " opFine=" + opValue(context, "android:fine_location")
                + " opCoarse=" + opValue(context, "android:coarse_location")
                + " uid=" + Process.myUid() + " pkg=" + context.getPackageName();
    }

    static void install(ClassLoader loader, HookConfig config) {
        if (!config.hasLocation()) {
            status = config.locationEnabled ? "invalid" : "off";
            return;
        }
        classLoader = loader;
        finePlan = new LocationPlan(config.latitude, config.longitude,
                config.locationSeed, false);
        coarsePlan = new LocationPlan(config.latitude, config.longitude,
                config.locationSeed, true);
        StringBuilder trace = new StringBuilder();
        try {
            Class<?> manager = XposedHelpers.findClass("android.location.LocationManager", loader);
            hookLastKnown(manager, trace);
            hookCurrent(manager, trace);
            hookUpdates(manager, trace);
            hookRemove(manager, trace);
            hookProviders(manager, trace);
        } catch (Throwable t) {
            trace.append("lm:").append(t).append(';');
        }
        try {
            Class<?> location = XposedHelpers.findClass("android.location.Location", loader);
            XposedBridge.hookAllMethods(location, "isFromMockProvider",
                    new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    });
        } catch (Throwable t) {
            trace.append("mock:").append(t).append(';');
        }
        try {
            Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", loader);
            XposedBridge.hookAllMethods(secure, "getInt", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && "location_mode".equals(param.args[1])) {
                        param.setResult(3);
                    }
                }
            });
            XposedBridge.hookAllMethods(secure, "getIntForUser", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && "location_mode".equals(param.args[1])) {
                        param.setResult(3);
                    }
                }
            });
            XposedBridge.hookAllMethods(secure, "getString", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2
                            && "location_providers_allowed".equals(param.args[1])) {
                        param.setResult("gps,network");
                    }
                }
            });
        } catch (Throwable t) {
            trace.append("settings:").append(t).append(';');
        }
        try {
            hookFused(loader, trace);
        } catch (Throwable t) {
            trace.append("fused:").append(t).append(';');
        }
        int mode = permissionMode();
        status = (mode == 0 ? "pending" : mode == 2 ? "fine" : "coarse")
                + (trace.length() == 0 ? "" : " " + trace);
        XposedBridge.log("ClaudeWilsonHook location status=" + status);
    }

    private static void hookLastKnown(Class<?> manager, StringBuilder trace) {
        try {
            XposedBridge.hookAllMethods(manager, "getLastKnownLocation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int mode = permissionMode();
                    if (mode == 0) {
                        return;
                    }
                    String provider = param.args.length > 0 && param.args[0] instanceof String
                            ? (String) param.args[0] : "gps";
                    param.setResult(fake(mode, provider));
                }
            });
        } catch (Throwable t) {
            trace.append("last:").append(t).append(';');
        }
    }

    private static void hookCurrent(Class<?> manager, StringBuilder trace) {
        try {
            XposedBridge.hookAllMethods(manager, "getCurrentLocation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int mode = permissionMode();
                    if (mode == 0) {
                        return;
                    }
                    Consumer consumer = (Consumer) find(param.args, Consumer.class);
                    if (consumer == null) {
                        return;
                    }
                    param.setResult(null);
                    final Location value = fake(mode, "gps");
                    dispatch(consumer, value, (Executor) find(param.args, Executor.class),
                            looper(param.args));
                }
            });
        } catch (Throwable t) {
            trace.append("current:").append(t).append(';');
        }
    }

    private static void hookUpdates(Class<?> manager, StringBuilder trace) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                int mode = permissionMode();
                if (mode == 0) {
                    return;
                }
                Object target = null;
                boolean gms = false;
                for (Object arg : param.args) {
                    if (arg instanceof LocationListener || arg instanceof android.app.PendingIntent
                            || (arg != null && arg.getClass().getName()
                                    .equals("com.google.android.gms.location.LocationCallback"))) {
                        target = arg;
                        gms = arg != null && arg.getClass().getName()
                                .equals("com.google.android.gms.location.LocationCallback");
                        break;
                    }
                }
                if (target == null) {
                    return;
                }
                long interval = 1000L;
                for (Object arg : param.args) {
                    if (arg instanceof Long && ((Long) arg) > 0L) {
                        interval = (Long) arg;
                        break;
                    }
                }
                if (gms) {
                    for (Object arg : param.args) {
                        if (arg == null || !"com.google.android.gms.location.LocationRequest"
                                .equals(arg.getClass().getName())) {
                            continue;
                        }
                        Object value = XposedHelpers.callMethod(arg, "getIntervalMillis");
                        if (value instanceof Long && ((Long) value) > 0L) {
                            interval = (Long) value;
                        }
                        break;
                    }
                }
                param.setResult(gms ? task(null) : null);
                register(target, interval, looper(param.args),
                        (Executor) find(param.args, Executor.class), mode, gms,
                        "requestSingleUpdate".equals(param.method.getName()));
            }
        };
        try {
            XposedBridge.hookAllMethods(manager, "requestLocationUpdates", hook);
        } catch (Throwable t) {
            trace.append("updates:").append(t).append(';');
        }
        try {
            XposedBridge.hookAllMethods(manager, "requestSingleUpdate", hook);
        } catch (Throwable t) {
            trace.append("single:").append(t).append(';');
        }
    }

    private static void hookRemove(Class<?> manager, StringBuilder trace) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    stop(arg);
                    if (arg instanceof LocationListener[]) {
                        for (LocationListener listener : (LocationListener[]) arg) {
                            stop(listener);
                        }
                    }
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(manager, "removeUpdates", hook);
        } catch (Throwable t) {
            trace.append("remove:").append(t).append(';');
        }
    }

    private static void hookProviders(Class<?> manager, StringBuilder trace) {
        try {
            XposedBridge.hookAllMethods(manager, "isProviderEnabled", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (permissionMode() == 0 || param.args.length == 0) {
                        return;
                    }
                    String provider = String.valueOf(param.args[0]);
                    if ("gps".equals(provider) || "network".equals(provider)
                            || "passive".equals(provider) || "fused".equals(provider)) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.hookAllMethods(manager, "isLocationEnabled", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (permissionMode() != 0) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.hookAllMethods(manager, "hasProvider", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (permissionMode() == 0 || param.args.length == 0) {
                        return;
                    }
                    String provider = String.valueOf(param.args[0]);
                    if ("gps".equals(provider) || "network".equals(provider)
                            || "passive".equals(provider)) {
                        param.setResult(true);
                    }
                }
            });
            XC_MethodHook listHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (permissionMode() == 0) {
                        return;
                    }
                    Object result = param.getResult();
                    List<String> providers = new ArrayList<>();
                    if (result instanceof List) {
                        for (Object value : (List<?>) result) {
                            if (value instanceof String) {
                                providers.add((String) value);
                            }
                        }
                    }
                    if (!providers.contains("gps")) {
                        providers.add("gps");
                    }
                    if (!providers.contains("network")) {
                        providers.add("network");
                    }
                    param.setResult(providers);
                }
            };
            XposedBridge.hookAllMethods(manager, "getAllProviders", listHook);
            XposedBridge.hookAllMethods(manager, "getProviders", listHook);
            XposedBridge.hookAllMethods(manager, "getBestProvider", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (permissionMode() != 0) {
                        param.setResult("gps");
                    }
                }
            });
        } catch (Throwable t) {
            trace.append("providers:").append(t).append(';');
        }
    }

    /** Play Services fused client: the path Claude and ChatGPT actually use when GMS is present. */
    private static void hookFused(ClassLoader loader, StringBuilder trace) {
        Class<?> fused = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.FusedLocationProviderClient", loader);
        if (fused == null) {
            trace.append("fused:lazy;");
            // GMS may not have been loaded yet. Install when its class reaches the target loader;
            // the alternative is a hook that silently misses every fused request.
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (fusedHooked || param.args.length == 0
                            || !(param.args[0] instanceof String)
                            || !"com.google.android.gms.location.FusedLocationProviderClient"
                                    .equals(param.args[0])) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof Class) {
                        installFused((Class<?>) result);
                    }
                }
            });
            return;
        }
        installFused(fused);
    }

    private static void ensureFused() {
        if (fusedHooked || classLoader == null) {
            return;
        }
        Class<?> fused = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.FusedLocationProviderClient", classLoader);
        if (fused != null) {
            installFused(fused);
        }
    }

    private static void installFused(Class<?> fused) {
        synchronized (LocationHooks.class) {
            if (fusedHooked) {
                return;
            }
            fusedHooked = true;
        }
        try {
            XposedBridge.hookAllMethods(fused, "getLastLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int mode = permissionMode();
                    if (mode == 0) {
                        return;
                    }
                    Object task = task(fake(mode, "gps"));
                    if (task != null) {
                        param.setResult(task);
                    }
                }
            });
            XposedBridge.hookAllMethods(fused, "getCurrentLocation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int mode = permissionMode();
                    if (mode == 0) {
                        return;
                    }
                    Object task = task(fake(mode, "gps"));
                    if (task != null) {
                        param.setResult(task);
                    }
                }
            });
            XC_MethodHook updates = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int mode = permissionMode();
                    if (mode == 0) {
                        return;
                    }
                    Object callback = null;
                    long interval = 1000L;
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName()
                                .equals("com.google.android.gms.location.LocationCallback")) {
                            callback = arg;
                        }
                        if (arg != null && arg.getClass().getName()
                                .equals("com.google.android.gms.location.LocationRequest")) {
                            Object value = XposedHelpers.callMethod(arg, "getIntervalMillis");
                            if (value instanceof Long && ((Long) value) > 0L) {
                                interval = (Long) value;
                            }
                        }
                    }
                    if (callback == null) {
                        return;
                    }
                    Object task = task(null);
                    if (task == null) {
                        return;
                    }
                    param.setResult(task);
                    register(callback, interval, looper(param.args),
                            (Executor) find(param.args, Executor.class), mode, true, false);
                }
            };
            XposedBridge.hookAllMethods(fused, "requestLocationUpdates", updates);
            XposedBridge.hookAllMethods(fused, "removeLocationUpdates", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        stop(arg);
                    }
                }
            });
            XposedBridge.log("ClaudeWilsonHook location fused hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("ClaudeWilsonHook location fused: " + t);
        }
    }

    private static void register(Object target, long interval, Looper looper, Executor executor,
            int mode, boolean gms, boolean oneShot) {
        stop(target);
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        Registration registration = new Registration(target, handler, executor, mode, gms,
                Math.max(MIN_INTERVAL_MS, interval), oneShot);
        REGISTRATIONS.put(target, registration);
        handler.post(registration);
    }

    private static void stop(Object target) {
        if (target == null) {
            return;
        }
        Registration registration = REGISTRATIONS.remove(target);
        if (registration != null) {
            registration.stop();
        }
    }

    private static void dispatch(Consumer consumer, Location value, Executor executor,
            Looper looper) {
        Runnable task = () -> {
            try {
                consumer.accept(value);
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook location callback: " + t);
            }
        };
        if (executor != null) {
            executor.execute(task);
        } else {
            new Handler(looper == null ? Looper.getMainLooper() : looper).post(task);
        }
    }

    private static Location fake(int mode, String provider) {
        served.incrementAndGet();
        LocationPlan plan = mode == 1 ? coarsePlan : finePlan;
        LocationFix fix = plan.fix(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());
        Location location = new Location(provider == null || provider.isEmpty() ? "gps" : provider);
        location.setLatitude(fix.latitude);
        location.setLongitude(fix.longitude);
        location.setAccuracy(fix.accuracy);
        if (fix.hasAltitude) {
            location.setAltitude(fix.altitude);
        } else {
            location.removeAltitude();
        }
        if (fix.hasSpeed) {
            location.setSpeed(fix.speed);
        } else {
            location.removeSpeed();
        }
        if (fix.hasBearing) {
            location.setBearing(fix.bearing);
        } else {
            location.removeBearing();
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (fix.hasAltitude) {
                location.setVerticalAccuracyMeters(fix.verticalAccuracyMeters);
            } else if (Build.VERSION.SDK_INT >= 33) {
                location.removeVerticalAccuracy();
            }
            if (fix.hasSpeed) {
                location.setSpeedAccuracyMetersPerSecond(fix.speedAccuracyMetersPerSecond);
            } else if (Build.VERSION.SDK_INT >= 33) {
                location.removeSpeedAccuracy();
            }
            if (fix.hasBearing) {
                location.setBearingAccuracyDegrees(fix.bearingAccuracyDegrees);
            } else if (Build.VERSION.SDK_INT >= 33) {
                location.removeBearingAccuracy();
            }
        }
        location.setTime(fix.timeMillis);
        location.setElapsedRealtimeNanos(fix.elapsedRealtimeNanos);
        Bundle extras = new Bundle();
        extras.putInt("satellites", fix.satellites);
        location.setExtras(extras);
        return location;
    }

    private static Object task(Object value) {
        if (classLoader == null) {
            return null;
        }
        try {
            Class<?> tasks = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.tasks.Tasks", classLoader);
            if (tasks == null) {
                return null;
            }
            Method method = tasks.getMethod("forResult", Object.class);
            method.setAccessible(true);
            return method.invoke(null, value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int permissionMode() {
        ensureFused();
        Context context = context();
        if (context == null) {
            return 0;
        }
        // Runtime permission is the gate. AppOps is deliberately not a veto here: inside a
        // Vector-specialized process on Android 15 it can report "ignore" even when the shell
        // shows COARSE_LOCATION allow (measured on SM-S938B), which would make the hook a no-op
        // while the target still holds the permission. The simulation is already an explicit
        // per-target choice; the diagnostic line records the AppOps value for evidence.
        if (context.checkSelfPermission(FINE) == 0) {
            return 2;
        }
        return context.checkSelfPermission(COARSE) == 0 ? 1 : 0;
    }

    private static int opValue(Context context, String op) {
        if (Build.VERSION.SDK_INT < 29) {
            return -1;
        }
        try {
            AppOpsManager manager = (AppOpsManager) context.getSystemService(
                    Context.APP_OPS_SERVICE);
            if (manager == null) {
                return -1;
            }
            int value = manager.unsafeCheckOpNoThrow(op, Process.myUid(),
                    context.getPackageName());
            // A foreground app can still read "ignore" from the public wrapper on some OEM
            // builds even though the raw op is allow; the raw value is the same grant without
            // the foreground projection.
            if (value == 1 && Build.VERSION.SDK_INT >= 31) {
                try {
                    int raw = manager.unsafeCheckOpRawNoThrow(op, Process.myUid(),
                            context.getPackageName());
                    if (raw == 0 || raw == 3 || raw == 4) {
                        return raw;
                    }
                } catch (Throwable ignored) {
                }
            }
            return value;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Context context() {
        Context current = appContext;
        if (current != null) {
            return current;
        }
        try {
            Class<?> thread = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Object application = XposedHelpers.callStaticMethod(thread, "currentApplication");
            if (application instanceof Context) {
                appContext = (Context) application;
                return appContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object find(Object[] args, Class<?> type) {
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return arg;
            }
        }
        return null;
    }

    private static Looper looper(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Looper) {
                return (Looper) arg;
            }
        }
        return null;
    }

    private static final class Registration implements Runnable {
        private final Object target;
        private final Handler handler;
        private final Executor executor;
        private final int mode;
        private final boolean gms;
        private final long interval;
        private final boolean oneShot;
        private volatile boolean active = true;
        private boolean announced;

        Registration(Object target, Handler handler, Executor executor, int mode, boolean gms,
                long interval, boolean oneShot) {
            this.target = target;
            this.handler = handler;
            this.executor = executor;
            this.mode = mode;
            this.gms = gms;
            this.interval = interval;
            this.oneShot = oneShot;
        }

        @Override public void run() {
            if (!active) {
                return;
            }
            deliver();
            if (!active) {
                return;
            }
            if (oneShot) {
                REGISTRATIONS.remove(target);
                stop();
                return;
            }
            handler.postDelayed(this, interval);
        }

        private void deliver() {
            if (gms) {
                deliverGms();
                return;
            }
            Location value = fake(mode, "gps");
            if (target instanceof android.app.PendingIntent) {
                deliverPending((android.app.PendingIntent) target, value);
                return;
            }
            LocationListener listener = (LocationListener) target;
            if (!announced) {
                announced = true;
                try {
                    listener.onProviderEnabled("gps");
                } catch (Throwable ignored) {
                }
            }
            deliverListener(listener, value);
        }

        private void deliverListener(LocationListener listener, Location value) {
            Runnable task = () -> {
                try {
                    if (hasListCallback(listener)) {
                        listener.onLocationChanged(Collections.singletonList(value));
                    } else {
                        listener.onLocationChanged(value);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("ClaudeWilsonHook location listener: " + t);
                }
            };
            if (executor != null) {
                executor.execute(task);
            } else if (Looper.myLooper() == handler.getLooper()) {
                task.run();
            } else {
                handler.post(task);
            }
        }

        private boolean hasListCallback(LocationListener listener) {
            try {
                listener.getClass().getMethod("onLocationChanged", List.class);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private void deliverPending(android.app.PendingIntent pending, Location value) {
            Context context = context();
            if (context == null) {
                return;
            }
            try {
                Intent intent = new Intent();
                intent.putExtra(LocationManager.KEY_LOCATION_CHANGED, value);
                pending.send(context, 0, intent);
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook location pending: " + t);
            }
        }

        private void deliverGms() {
            if (classLoader == null) {
                return;
            }
            try {
                Class<?> resultClass = XposedHelpers.findClass(
                        "com.google.android.gms.location.LocationResult", classLoader);
                Method create = resultClass.getDeclaredMethod("create", List.class);
                create.setAccessible(true);
                Object result = create.invoke(null,
                        Collections.singletonList(fake(mode, "gps")));
                Runnable task = () -> {
                    try {
                        XposedHelpers.callMethod(target, "onLocationResult", result);
                    } catch (Throwable t) {
                        XposedBridge.log("ClaudeWilsonHook location fused: " + t);
                    }
                };
                if (executor != null) {
                    executor.execute(task);
                } else if (Looper.myLooper() == handler.getLooper()) {
                    task.run();
                } else {
                    handler.post(task);
                }
            } catch (Throwable t) {
                XposedBridge.log("ClaudeWilsonHook location fused result: " + t);
            }
        }

        void stop() {
            active = false;
            handler.removeCallbacks(this);
        }
    }
}
