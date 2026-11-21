package com.walter.claudewilson;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import com.walter.claudewilson.model.LocationFacts;
import com.walter.claudewilson.model.PermissionFacts;
import com.walter.claudewilson.model.TargetSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the target's permission facts without touching the target process.
 *
 * <p>M1: manifest declarations and runtime grants through {@link PackageManager}; the device
 * location mode through {@code Settings.Secure}. M2: {@code cmd appops get} through the same
 * root/Shizuku channel the rest of the app uses. No coordinate is read: {@code dumpsys location}
 * is deliberately not parsed.
 */
final class TargetPermissionCollector {
    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private static final String BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION";
    private static final String CALENDAR_READ = "android.permission.READ_CALENDAR";
    private static final String CALENDAR_WRITE = "android.permission.WRITE_CALENDAR";
    private static final String MICROPHONE = "android.permission.RECORD_AUDIO";
    private static final String SCREEN_CAPTURE = "android.permission.DETECT_SCREEN_CAPTURE";
    private static final String NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES";
    private static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final String BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String ACTIVITY = "android.permission.ACTIVITY_RECOGNITION";
    private static final String BODY_SENSORS = "android.permission.BODY_SENSORS";
    private static final Pattern OP = Pattern.compile("([A-Z_]+_LOCATION):\\s*(\\w+)");
    private static final long FAST_CACHE_MS = 60000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFacts> CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, CachedFacts>();

    static final class CachedFacts {
        final PermissionFacts permissions;
        final LocationFacts location;
        final long capturedAt;

        CachedFacts(PermissionFacts permissions, LocationFacts location, long capturedAt) {
            this.permissions = permissions;
            this.location = location;
            this.capturedAt = capturedAt;
        }
    }

    /** Batched collector: 1 single shell call on full scan, 0ms cached hit on fast reconcile. */
    CachedFacts collect(Context context, TargetSpec target, boolean fast) {
        String key = target == null ? "" : target.id();
        long now = android.os.SystemClock.elapsedRealtime();
        if (fast && !key.isEmpty()) {
            CachedFacts hit = CACHE.get(key);
            if (hit != null && (now - hit.capturedAt) < FAST_CACHE_MS) {
                return hit;
            }
        }
        String appOpsDump = "";
        if (target != null && RootShell.shellReady()
                && com.walter.claudewilson.model.ShellQuote.packageName(target.packageName())) {
            String pkg = target.packageName();
            appOpsDump = RootShell.run(
                    "cmd appops get " + pkg + " FINE_LOCATION 2>/dev/null; "
                    + "cmd appops get " + pkg + " COARSE_LOCATION 2>/dev/null; "
                    + "cmd appops get " + pkg + " MOCK_LOCATION 2>/dev/null", 4096);
            if (appOpsDump == null || appOpsDump.startsWith("exc:")
                    || appOpsDump.startsWith("timeout:")) {
                appOpsDump = "";
            }
        }
        CachedFacts fresh = new CachedFacts(
                permissionsWithDump(context, target, appOpsDump),
                locationWithDump(context, target, appOpsDump),
                now);
        if (!key.isEmpty()) {
            CACHE.put(key, fresh);
        }
        return fresh;
    }

    PermissionFacts permissions(Context context, TargetSpec target) {
        return collect(context, target, false).permissions;
    }

    private PermissionFacts permissionsWithDump(Context context, TargetSpec target, String appOpsDump) {
        PermissionFacts.Builder builder = new PermissionFacts.Builder();
        if (context == null || target == null) {
            return builder.readable(false).build();
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    target.packageName(), PackageManager.GET_PERMISSIONS);
            Map<String, Boolean> declared = new LinkedHashMap<>();
            Map<String, Boolean> granted = new LinkedHashMap<>();
            if (info.requestedPermissions != null) {
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    String name = info.requestedPermissions[i];
                    boolean isGranted = info.requestedPermissionsFlags != null
                            && i < info.requestedPermissionsFlags.length
                            && (info.requestedPermissionsFlags[i]
                                    & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    declared.put(name, true);
                    granted.put(name, isGranted);
                }
            }
            boolean fine = Boolean.TRUE.equals(granted.get(FINE));
            boolean coarse = Boolean.TRUE.equals(granted.get(COARSE));
            builder.put("location.fine", declared.containsKey(FINE), fine);
            if (!fine && coarse && declared.containsKey(FINE)) {
                builder.approximate("location.fine");
            }
            builder.put("location.coarse", declared.containsKey(COARSE), coarse);
            builder.put("location.background", declared.containsKey(BACKGROUND),
                    Boolean.TRUE.equals(granted.get(BACKGROUND)));
            builder.put("calendar", declared.containsKey(CALENDAR_READ)
                            || declared.containsKey(CALENDAR_WRITE),
                    Boolean.TRUE.equals(granted.get(CALENDAR_READ))
                            || Boolean.TRUE.equals(granted.get(CALENDAR_WRITE)));
            builder.put("microphone", declared.containsKey(MICROPHONE),
                    Boolean.TRUE.equals(granted.get(MICROPHONE)));
            builder.put("screen_capture", declared.containsKey(SCREEN_CAPTURE),
                    Boolean.TRUE.equals(granted.get(SCREEN_CAPTURE)));
            builder.put("wifi", declared.containsKey(NEARBY_WIFI),
                    Boolean.TRUE.equals(granted.get(NEARBY_WIFI)));
            if (android.os.Build.VERSION.SDK_INT >= 37) {
                builder.put("local_network", declared.containsKey(LOCAL_NETWORK),
                        Boolean.TRUE.equals(granted.get(LOCAL_NETWORK)));
            }
            builder.put("bluetooth_scan", declared.containsKey(BT_SCAN),
                    Boolean.TRUE.equals(granted.get(BT_SCAN)));
            builder.put("bluetooth_connect", declared.containsKey(BT_CONNECT),
                    Boolean.TRUE.equals(granted.get(BT_CONNECT)));
            builder.put("activity_recognition", declared.containsKey(ACTIVITY),
                    Boolean.TRUE.equals(granted.get(ACTIVITY)));
            builder.put("body_sensors", declared.containsKey(BODY_SENSORS),
                    Boolean.TRUE.equals(granted.get(BODY_SENSORS)));
            boolean healthDeclared = false;
            boolean healthGranted = false;
            for (String name : declared.keySet()) {
                if (name.startsWith("android.permission.health.")) {
                    healthDeclared = true;
                    healthGranted |= Boolean.TRUE.equals(granted.get(name));
                }
            }
            builder.put("health", healthDeclared, healthGranted);
            if (appOpsDump != null && !appOpsDump.isEmpty()) {
                Matcher matcher = OP.matcher(appOpsDump);
                while (matcher.find()) {
                    String opName = matcher.group(1);
                    if ("FINE_LOCATION".equals(opName)) {
                        builder.appOp("location.fine", matcher.group(2));
                        builder.source("package-manager+appops");
                    } else if ("COARSE_LOCATION".equals(opName)) {
                        builder.appOp("location.coarse", matcher.group(2));
                        builder.source("package-manager+appops");
                    }
                }
            }
            return builder.readable(true).build();
        } catch (Throwable t) {
            return builder.readable(false).source("package-manager").build();
        }
    }

    LocationFacts location(Context context, TargetSpec target) {
        return collect(context, target, false).location;
    }

    private LocationFacts locationWithDump(Context context, TargetSpec target, String appOpsDump) {
        LocationFacts.Builder builder = new LocationFacts.Builder();
        if (context == null) {
            return builder.readable(false).build();
        }
        boolean readable = false;
        try {
            int mode = Settings.Secure.getInt(context.getContentResolver(), "location_mode", -1);
            if (mode >= 0) {
                readable = true;
                builder.mode(mode == 0 ? LocationFacts.Mode.OFF
                        : mode == 1 ? LocationFacts.Mode.SENSORS
                        : mode == 2 ? LocationFacts.Mode.BATTERY
                        : LocationFacts.Mode.HIGH);
            }
        } catch (Throwable ignored) {
        }
        try {
            String providers = Settings.Secure.getString(
                    context.getContentResolver(), "location_providers_allowed");
            if (providers != null) {
                readable = true;
                String normalized = providers.replace("+", "");
                builder.providers(normalized);
                builder.gps(normalized.contains("gps"));
                builder.network(normalized.contains("network"));
            }
        } catch (Throwable ignored) {
        }

        if (appOpsDump != null && !appOpsDump.isEmpty()) {
            Matcher mock = Pattern.compile("MOCK_LOCATION:\\s*(\\w+)").matcher(appOpsDump);
            if (mock.find()) {
                builder.mock(!"ignore".equals(mock.group(1)));
                readable = true;
                builder.source("settings+appops");
            }
        }
        return builder.readable(readable).build();
    }
}
