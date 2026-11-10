package com.walter.claudewilson.model;

/**
 * One synthetic location fix, independent of {@code android.location.Location}.
 *
 * <p>This is the same shape VirtualRegion builds in
 * {@code com.bumptech.glide.d#H(ModuleSnapshot, ScopedApplicationPolicy, Location, ...)}:
 * WGS-84 degrees, optional altitude, accuracy/speed/bearing and their accuracy components,
 * wall-clock + elapsed-realtime timestamps, and a satellite count. The hook layer copies these
 * fields into the platform object at the last moment.
 */
public final class LocationFix {
    public final double latitude;
    public final double longitude;
    public final double altitude;
    public final float accuracy;
    public final float speed;
    public final float bearing;
    public final float verticalAccuracyMeters;
    public final float speedAccuracyMetersPerSecond;
    public final float bearingAccuracyDegrees;
    public final boolean hasAltitude;
    public final boolean hasSpeed;
    public final boolean hasBearing;
    public final long timeMillis;
    public final long elapsedRealtimeNanos;
    public final int satellites;

    public LocationFix(double latitude, double longitude, double altitude, float accuracy,
            float speed, float bearing, float verticalAccuracyMeters,
            float speedAccuracyMetersPerSecond, float bearingAccuracyDegrees,
            boolean hasAltitude, boolean hasSpeed, boolean hasBearing,
            long timeMillis, long elapsedRealtimeNanos, int satellites) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.accuracy = accuracy;
        this.speed = speed;
        this.bearing = bearing;
        this.verticalAccuracyMeters = verticalAccuracyMeters;
        this.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond;
        this.bearingAccuracyDegrees = bearingAccuracyDegrees;
        this.hasAltitude = hasAltitude;
        this.hasSpeed = hasSpeed;
        this.hasBearing = hasBearing;
        this.timeMillis = timeMillis;
        this.elapsedRealtimeNanos = elapsedRealtimeNanos;
        this.satellites = satellites;
    }
}
