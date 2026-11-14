package com.swiftdrop.pricing.internal.domain;

/**
 * The great-circle (straight-line, over the Earth's curve) distance between two points, by the Haversine formula.
 * double is fine here: this is geometry, not money.
 */
public final class GeoDistance {

    /** The Earth's mean radius. */
    static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() {
    }

    /** Kilometres between two points given in degrees. */
    public static double haversineKm(double latitude1, double longitude1, double latitude2, double longitude2) {
        double phi1 = Math.toRadians(latitude1);
        double phi2 = Math.toRadians(latitude2);
        double deltaPhi = Math.toRadians(latitude2 - latitude1);
        double deltaLambda = Math.toRadians(longitude2 - longitude1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
