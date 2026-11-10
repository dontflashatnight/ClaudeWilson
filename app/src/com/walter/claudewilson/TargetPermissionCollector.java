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
    private static final Pattern OP = Pattern.compile("([A-Z_]+_LOCATION):\\s*(\\w+)");

    PermissionFacts permissions(Context context, TargetSpec target) {
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
            boolean healthDeclared = false;
            boolean healthGranted = false;
            for (String name : declared.keySet()) {
                if (name.startsWith("android.permission.health.")) {
                    healthDeclared = true;
                    healthGranted |= Boolean.TRUE.equals(granted.get(name));
                }
            }
            builder.put("health", healthDeclared, healthGranted);
            if (target != null && RootShell.shellReady()) {
                for (String op : new String[]{"FINE_LOCATION", "COARSE_LOCATION"}) {
                    String dump = RootShell.run("cmd appops get " + target.packageName()
                            + " " + op + " 2>/dev/null", 2048);
                    if (dump == null || dump.startsWith("exc:")
                            || dump.startsWith("timeout:")) {
                        continue;
                    }
                    Matcher matcher = OP.matcher(dump);
                    while (matcher.find()) {
                        String key = matcher.group(1).startsWith("FINE")
                                ? "location.fine" : "location.coarse";
                        builder.appOp(key, matcher.group(2));
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

        if (target != null && RootShell.shellReady()) {
            String dump = RootShell.run("cmd appops get " + target.packageName()
                    + " MOCK_LOCATION 2>/dev/null", 2048);
            if (dump != null && !dump.startsWith("exc:") && !dump.startsWith("timeout:")) {
                Matcher mock = Pattern.compile("MOCK_LOCATION:\\s*(\\w+)").matcher(dump);
                if (mock.find()) {
                    builder.mock(!"ignore".equals(mock.group(1)));
                    readable = true;
                    builder.source("settings+appops");
                }
            }
        }
        return builder.readable(readable).build();
    }
}
