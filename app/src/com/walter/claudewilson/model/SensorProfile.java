package com.walter.claudewilson.model;

import java.util.Locale;
import java.util.Random;

/**
 * One persona's sensor baseline. The catalog gives the device-class noise/light/pressure range;
 * the seed and region give the actual resting orientation, magnetic field, ambient light and
 * pressure. This replaces the old fixed arrays so two users do not report identical sensors.
 */
public final class SensorProfile {
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
        double tiltX = (random.nextDouble() - 0.5d) * 0.22d;
        double tiltY = (random.nextDouble() - 0.5d) * 0.22d;
        double gx = Math.sin(tiltY) * 9.81d + (random.nextDouble() - 0.5d) * noise;
        double gy = -Math.sin(tiltX) * 9.81d + (random.nextDouble() - 0.5d) * noise;
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
        boolean day = localHour >= 7 && localHour < 19;
        double ambient = day ? lightDay : lightNight;
        float light = (float) Math.max(0.0d, ambient
                + (random.nextDouble() - 0.5d) * (day ? 60.0d : 4.0d));
        double altitude = 5.0d + Math.floorMod(seed, 120L);
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
