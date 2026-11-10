package com.walter.claudewilson.model;

/**
 * Deterministic fixed-point location plan, ported from VirtualRegion's fixed mode.
 *
 * <p>Source behavior (VirtualRegion 2.0.5, jadx):
 * {@code M3.D} is the environment centre, {@code V3.n#b} turns it into a {@code C3.t} fix, and
 * {@code C3.w#c} applies a slow deterministic offset bounded by the configured radius. The
 * default environment uses a 4 m radius and a 5 m accuracy. A coarse-only app receives the same
 * fix snapped to a 3 km grid, with accuracy raised to 3 km and altitude/speed/bearing removed
 * ({@code G3.u0#H}).
 *
 * <p>No route mode here: the port covers the fixed environment point, which is what the region
 * switcher writes. A route would add a per-target polyline and a transport layer, neither of
 * which belongs in this first port.
 */
public final class LocationPlan {
    private static final double METERS_PER_DEGREE = 111320.0d;
    private static final double JITTER_RADIUS_METERS = 4.0d;
    private static final double BASE_ACCURACY_METERS = 5.0d;
    private static final double COARSE_ACCURACY_METERS = 3000.0d;
    private static final double COARSE_LATITUDE_STEP = 3000.0d / METERS_PER_DEGREE;
    private static final double ALTITUDE_METERS = 35.0d;
    private static final long TICK_NANOS = 1000000000L;

    public final double latitude;
    public final double longitude;
    public final long seed;
    public final boolean coarse;

    public LocationPlan(double latitude, double longitude, long seed, boolean coarse) {
        if (!valid(latitude, longitude)) {
            throw new IllegalArgumentException("invalid coordinates");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.seed = seed;
        this.coarse = coarse;
    }

    public static boolean valid(double latitude, double longitude) {
        return Double.isFinite(latitude) && latitude >= -90.0d && latitude <= 90.0d
                && Double.isFinite(longitude) && longitude >= -180.0d && longitude <= 180.0d;
    }

    /** One fix at the given clock. The caller supplies both clocks; the model reads none. */
    public LocationFix fix(long nowMillis, long elapsedRealtimeNanos) {
        double lat = latitude;
        double lng = longitude;
        long tick = Math.floorDiv(Math.max(0L, elapsedRealtimeNanos), TICK_NANOS);
        long block = Math.floorDiv(tick, 8L);
        double phase = (double) Math.floorMod(tick, 8L) / 8.0d;
        double smooth = phase * phase * (3.0d - 2.0d * phase);

        double x0 = noise(seed, block, 0);
        double y0 = noise(seed, block, 1);
        double x1 = noise(seed, block + 1L, 0);
        double y1 = noise(seed, block + 1L, 1);
        double dx = (x0 + (x1 - x0) * smooth) * 0.875d * JITTER_RADIUS_METERS;
        double dy = (y0 + (y1 - y0) * smooth) * 0.875d * JITTER_RADIUS_METERS;
        double distance = Math.hypot(dx, dy);
        if (distance > JITTER_RADIUS_METERS) {
            double scale = JITTER_RADIUS_METERS / distance;
            dx *= scale;
            dy *= scale;
        }

        double accuracy = BASE_ACCURACY_METERS;
        long accuracyBlock = Math.floorDiv(tick, 8L);
        double accuracyNoise = noise(seed, accuracyBlock, 2);
        accuracy = Math.max(0.5d, accuracy * (1.0d + 0.12d * accuracyNoise));

        lat = clamp(lat + dy / METERS_PER_DEGREE, -90.0d, 90.0d);
        double cos = Math.max(1.0E-6d, Math.abs(Math.cos(Math.toRadians(lat))));
        lng = wrap(lng + dx / (cos * METERS_PER_DEGREE));

        boolean hasAltitude = true;
        boolean hasSpeed = true;
        boolean hasBearing = true;
        if (coarse) {
            double lngStep = COARSE_ACCURACY_METERS / (cos * METERS_PER_DEGREE);
            lat = clamp(Math.rint(lat / COARSE_LATITUDE_STEP) * COARSE_LATITUDE_STEP,
                    -90.0d, 90.0d);
            lng = wrap(Math.rint(lng / lngStep) * lngStep);
            accuracy = Math.max(accuracy, COARSE_ACCURACY_METERS);
            hasAltitude = false;
            hasSpeed = false;
            hasBearing = false;
        }

        float accuracyMeters = (float) accuracy;
        return new LocationFix(lat, lng, ALTITUDE_METERS, accuracyMeters,
                0.0f, 0.0f,
                (float) Math.max(1.5d, accuracy * 1.5d),
                (float) Math.max(0.15d, Math.min(1.5d, accuracy * 0.12d)),
                12.0f,
                hasAltitude, hasSpeed, hasBearing,
                nowMillis, Math.max(0L, elapsedRealtimeNanos), 20);
    }

    /** VirtualRegion {@code C3.v#b}: a stable value in [-1, 1) for a seed/tick/channel. */
    static double noise(long seed, long tick, int channel) {
        long h = (seed * -7046029254386353131L) ^ (tick * -4658895280553007687L)
                ^ ((long) channel * -7723592293110705685L);
        h = (h ^ (h >>> 30)) * -4658895280553007687L;
        h = (h ^ (h >>> 27)) * -7723592293110705685L;
        return ((((h ^ (h >>> 31)) >>> 11) * 1.1102230246251565E-16d) * 2.0d) - 1.0d;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double wrap(double longitude) {
        double value = longitude % 360.0d;
        if (value >= 180.0d) {
            value -= 360.0d;
        } else if (value < -180.0d) {
            value += 360.0d;
        }
        return value;
    }
}
