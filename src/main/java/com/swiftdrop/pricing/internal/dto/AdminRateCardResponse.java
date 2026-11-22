package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.pricing.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A rate card with its vehicles (smallest first): the body of GET /admin/pricing/rate-card (the active one) and one
 * item of GET /admin/pricing/rate-cards (every version). Read only: rate cards change only through Flyway migrations.
 *
 * @param active            true for the one card quotes use now; parcels keep the version they were priced with
 * @param baseWeightKg      billable kilograms included before {@code perKgFee} applies
 * @param volumetricDivisor volumetric weight (kg) = length x width x height (cm) / this
 * @param linehaulPerKm     for each kilometre between the pickup and drop-off branches
 * @param roadFactor        straight-line distance between district centres x this = road distance
 */
public record AdminRateCardResponse(
        int version,
        boolean active,
        String currency,
        BigDecimal baseFee,
        int baseWeightKg,
        BigDecimal perKgFee,
        int volumetricDivisor,
        BigDecimal codFee,
        BigDecimal linehaulPerKm,
        BigDecimal roadFactor,
        Instant createdAt,
        List<VehicleLimit> vehicles) {

    public AdminRateCardResponse {
        vehicles = List.copyOf(vehicles);
    }

    /**
     * One vehicle of the rate card: the biggest parcel it takes and the fixed price of one local leg.
     *
     * @param checkOrder vehicles are tried from the lowest; the first that carries and fits the parcel is used
     * @param legFee     the price of the pickup leg, and again of the delivery leg
     */
    public record VehicleLimit(Vehicle vehicle, int checkOrder, int maxLengthCm, int maxWidthCm, int maxHeightCm,
                               BigDecimal maxWeightKg, BigDecimal legFee) {
    }
}
