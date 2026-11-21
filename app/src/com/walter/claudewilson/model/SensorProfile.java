package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.Random;

/**
 * One persona's sensor baseline. The catalog gives the device-class noise/light/pressure range;
 * the seed and region give the actual resting orientation, magnetic field, ambient light and
 * pressure. This replaces the old fixed arrays so two users do not report identical sensors.
 */
public final class SensorProfile {
    public static final String PRESET_AUTO = "auto";
    public static final String PRESET_DESK = "desk";
    public static final String PRESET_HANDHELD = "handheld";
    public static final String PRESET_INDOOR_NIGHT = "indoor_night";
    public static final String PRESET_OUTDOOR_DAY = "outdoor_day";

    public final float[] accelerometer;
    public final float[] gyroscope;
    public final float[] magnetometer;
    public final float light;
    public final float pressure;
    public final float proximity;

    private SensorProfile(float[] accelerometer, float[] gyroscope, float[] magnetometer,
            float light, float pressure, float proximity) {
        this.accelerometer = accelerometer;
        this.gyroscope = gyroscope;
        this.magnetometer = magnetometer;
        this.light = light;
        this.pressure = pressure;
        this.proximity = proximity;
    }

    /** @param params class|accel_noise|gyro_bias|light_day|light_night|pressure_base|proximity */
    public static SensorProfile forRegion(long seed, double latitude, double longitude,
            int localHour, String[] params) {
        return forPreset(seed, latitude, longitude, localHour, params, PRESET_AUTO, Double.NaN);
    }

    /** Synthesizes a physically coherent 6-sensor baseline for a named posture/environment preset. */
    public static SensorProfile forPreset(long seed, double latitude, double longitude,
            int localHour, String[] params, String preset, double customAltitude) {
        String mode = preset == null || preset.isEmpty() ? PRESET_AUTO : preset;
        Random random = new Random(seed ^ 0x53454E53L);
        double noise = 0.03d;
        double bias = 0.002d;
        double lightDay = 420.0d;
        double lightNight = 12.0d;
        double pressureBase = 1013.0d;
        double proximity = 0.0d;
        if (params != null && params.length >= 7) {
            try {
                noise = Double.parseDouble(params[1].trim());
                bias = Double.parseDouble(params[2].trim());
                lightDay = Double.parseDouble(params[3].trim());
                lightNight = Double.parseDouble(params[4].trim());
                pressureBase = Double.parseDouble(params[5].trim());
                proximity = Double.parseDouble(params[6].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        double tiltX;
        double tiltY;
        if (PRESET_DESK.equals(mode)) {
            tiltX = (random.nextDouble() - 0.5d) * 0.04d;
            tiltY = (random.nextDouble() - 0.5d) * 0.04d;
            noise *= 0.45d;
            bias *= 0.35d;
        } else if (PRESET_HANDHELD.equals(mode)) {
            tiltX = 0.58d + (random.nextDouble() - 0.5d) * 0.24d;
            tiltY = (random.nextDouble() - 0.5d) * 0.16d;
            noise *= 2.2d;
            bias *= 3.5d;
        } else {
            tiltX = (random.nextDouble() - 0.5d) * 0.22d;
            tiltY = (random.nextDouble() - 0.5d) * 0.22d;
        }
        double gx = Math.sin(tiltY) * 9.81d + (random.nextDouble() - 0.5d) * noise;
        double gy = Math.sin(tiltX) * 9.81d + (random.nextDouble() - 0.5d) * noise;
        double gz = Math.cos(tiltX) * Math.cos(tiltY) * 9.81d
                + (random.nextDouble() - 0.5d) * noise;
        float[] accel = {(float) gx, (float) gy, (float) gz};
        float[] gyro = {
                (float) ((random.nextDouble() - 0.5d) * bias),
                (float) ((random.nextDouble() - 0.5d) * bias),
                (float) ((random.nextDouble() - 0.5d) * bias)
        };
        // A slowly varying field with a longitude-dependent declination.
        double declination = Math.toRadians(Math.max(-20.0d, Math.min(20.0d,
                longitude / 18.0d)));
        float[] mag = {
                (float) (18.0d + Math.cos(declination) * 6.0d + random.nextGaussian()),
                (float) (-32.0d + Math.sin(declination) * 8.0d + random.nextGaussian()),
                (float) (41.0d + Math.cos(latitude * Math.PI / 180.0d) * 4.0d
                        + random.nextGaussian())
        };
        double ambient;
        double spread;
        if (PRESET_INDOOR_NIGHT.equals(mode)) {
            ambient = Math.max(4.0d, lightNight);
            spread = 4.0d;
        } else if (PRESET_OUTDOOR_DAY.equals(mode)) {
            ambient = 4800.0d;
            spread = 900.0d;
        } else if (PRESET_DESK.equals(mode)) {
            ambient = 260.0d;
            spread = 35.0d;
        } else {
            boolean day = localHour >= 7 && localHour < 19;
            ambient = day ? lightDay : lightNight;
            spread = day ? 60.0d : 4.0d;
        }
        float light = (float) Math.max(0.0d, ambient + (random.nextDouble() - 0.5d) * spread);
        double altitude = !Double.isNaN(customAltitude) && customAltitude >= -100.0d && customAltitude <= 9000.0d
                ? customAltitude
                : (5.0d + Math.floorMod(seed, 120L));
        float pressure = (float) (pressureBase * Math.exp(-altitude / 8434.0d)
                + (random.nextDouble() - 0.5d) * 0.6d);
        return new SensorProfile(accel, gyro, mag, light, pressure, (float) proximity);
    }

    public String json() {
        return "{\"accelerometer\":[" + f(accelerometer[0]) + "," + f(accelerometer[1]) + ","
                + f(accelerometer[2]) + "],\"gyroscope\":[" + f(gyroscope[0]) + ","
                + f(gyroscope[1]) + "," + f(gyroscope[2]) + "],\"magnetometer\":["
                + f(magnetometer[0]) + "," + f(magnetometer[1]) + ","
                + f(magnetometer[2]) + "],\"light\":" + f(light)
                + ",\"pressure\":" + f(pressure)
                + ",\"proximity\":" + f(proximity) + "}";
    }

    private static String f(float value) {
        return String.format(Locale.US, "%.5f", value);
    }
}
