package com.swiftdrop.pricing.internal.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The prices of one rate card version (pricing.rate_cards with its vehicle_rates). A booking keeps the version it
 * was quoted with, so the quote can be worked out again later.
 *
 * @param baseFee            covers the first {@code baseWeightKg} billable kilograms
 * @param perKgFee           for every billable kilogram above them
 * @param volumetricDivisor  volumetric weight (kg) = length x width x height (cm) / this
 * @param codFee             handling fee when the courier collects cash on delivery
 * @param linehaulPerKm      for each kilometre between the pickup and drop-off district branches (SwiftDrop's own
 *                           transport, whatever the parcel's size)
 * @param roadFactor         straight-line distance x this = estimated road distance
 * @param vehicles           sorted by check order, smallest vehicle first
 */
public record RateCard(
        int version,
        String currency,
        BigDecimal baseFee,
        int baseWeightKg,
        BigDecimal perKgFee,
        int volumetricDivisor,
        BigDecimal codFee,
        BigDecimal linehaulPerKm,
        BigDecimal roadFactor,
        List<VehicleRate> vehicles) {

    public RateCard {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(perKgFee, "perKgFee");
        Objects.requireNonNull(codFee, "codFee");
        Objects.requireNonNull(linehaulPerKm, "linehaulPerKm");
        Objects.requireNonNull(roadFactor, "roadFactor");
        if (volumetricDivisor <= 0) {
            throw new IllegalArgumentException("volumetricDivisor must be more than 0");
        }
        vehicles = vehicles.stream().sorted(Comparator.comparingInt(VehicleRate::checkOrder)).toList();
    }
}
