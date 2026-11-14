package com.swiftdrop.pricing.internal.strategy;

import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;

/**
 * The estimated road distance from a pickup to a drop-off district, in kilometres with one decimal. A strategy: today
 * {@link DistrictCentreDistance} (district centres, straight line x road factor); a routing service such as Google
 * Routes could be another implementation without changing the fee calculator.
 */
public interface DistanceCalculator {

    /**
     * @param roadFactor the rate card's road factor; an implementation that knows real road distances ignores it
     * @throws IllegalStateException when a district has no known location
     */
    BigDecimal roadKm(District from, District to, BigDecimal roadFactor);
}
