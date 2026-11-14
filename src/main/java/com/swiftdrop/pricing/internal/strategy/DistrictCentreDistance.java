package com.swiftdrop.pricing.internal.strategy;

import com.swiftdrop.pricing.internal.domain.DistrictLocation;
import com.swiftdrop.pricing.internal.domain.GeoDistance;
import com.swiftdrop.pricing.internal.persistence.DistrictLocationRepository;
import com.swiftdrop.shared.reference.District;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Distance between district centres: the Haversine (straight-line) distance x the road factor, because roads are
 * longer than a straight line. A trip inside one district counts as 0 km. The 25 centres are static data, so they
 * are read from the database once and kept in memory.
 */
@Component
class DistrictCentreDistance implements DistanceCalculator {

    static final BigDecimal NO_DISTANCE = new BigDecimal("0.0");

    private final DistrictLocationRepository locations;
    /** Loaded on first use; a race only loads the same rows twice. */
    private volatile Map<District, Centre> centres;

    DistrictCentreDistance(DistrictLocationRepository locations) {
        this.locations = locations;
    }

    @Override
    public BigDecimal roadKm(District from, District to, BigDecimal roadFactor) {
        if (from == to) {
            return NO_DISTANCE;
        }
        Centre a = centre(from);
        Centre b = centre(to);
        double straightKm = GeoDistance.haversineKm(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        return BigDecimal.valueOf(straightKm).multiply(roadFactor).setScale(1, RoundingMode.HALF_UP);
    }

    private Centre centre(District district) {
        Map<District, Centre> loaded = centres;
        if (loaded == null) {
            loaded = locations.findAll().stream()
                    .collect(Collectors.toUnmodifiableMap(DistrictLocation::getDistrict, Centre::of,
                            (first, second) -> first));
            centres = loaded;
        }
        Centre centre = loaded.get(district);
        if (centre == null) {
            throw new IllegalStateException("No location for district " + district);
        }
        return centre;
    }

    /** A centre in degrees, kept instead of the entity. */
    private record Centre(double latitude, double longitude) {

        static Centre of(DistrictLocation location) {
            return new Centre(location.getLatitude().doubleValue(), location.getLongitude().doubleValue());
        }
    }
}
