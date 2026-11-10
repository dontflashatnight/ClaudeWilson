package com.walter.claudewilson.model;

/**
 * Equirectangular world projection used by the offline map picker.
 *
 * <p>x runs 0..1 from 180 W to 180 E, y runs 0..1 from 90 N to 90 S. The map image and the touch
 * handler share exactly this function, so a tap and a drawn pin cannot disagree.
 */
public final class MapProjection {
    public static final double MAX_LATITUDE = 85.0d;

    private MapProjection() {
    }

    public static double x(double longitude) {
        return (clampLongitude(longitude) + 180.0d) / 360.0d;
    }

    public static double y(double latitude) {
        return (90.0d - clampLatitude(latitude)) / 180.0d;
    }

    public static double longitudeAt(double x) {
        return clampLongitude(x * 360.0d - 180.0d);
    }

    public static double latitudeAt(double y) {
        return clampLatitude(90.0d - y * 180.0d);
    }

    public static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    public static double clampLongitude(double longitude) {
        double value = longitude % 360.0d;
        if (value >= 180.0d) {
            value -= 360.0d;
        } else if (value < -180.0d) {
            value += 360.0d;
        }
        return value;
    }
}
