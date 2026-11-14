package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent;
import com.swiftdrop.pricing.internal.domain.FeeQuote.FeeLine;
import com.swiftdrop.pricing.internal.domain.FeeQuote.QuoteStatus;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Works out the estimated delivery fee from a rate card. Plain Java: no database and no Spring, so every rule is
 * unit tested.
 *
 * <p>A parcel goes pickup address → pickup district branch → drop-off district branch → drop-off address. Only the
 * two local legs depend on the vehicle; between the branches SwiftDrop's own transport carries it.
 *
 * <pre>
 * fee = base fee, when the rate card has one (none since rate card 5)
 *     + per-kg fee x billable kg above the base kg (the first kg is included)
 *     + the vehicle's leg fee for the pickup leg
 *     + the vehicle's leg fee for the delivery leg
 *     + linehaul per km x the distance between the branches (0 inside one district)
 *     + the COD fee, when the courier collects cash and the rate card has one (none since rate card 3)
 * </pre>
 *
 * Billable weight is the greater of the actual and the volumetric weight (L x W x H / divisor), rounded up to a
 * whole kilogram. The vehicle is the first, smallest first, that carries the actual weight and fits the parcel.
 */
public class FeeCalculator {

    /**
     * @param branchDistanceKm the estimated road distance between the pickup and drop-off district branches
     * @param cashOnDelivery   true when the courier collects cash, which adds the rate card's COD fee if it has one
     *                         (the cash itself is not part of the fee)
     */
    public FeeQuote quote(RateCard card, ParcelMeasurements parcel, BigDecimal branchDistanceKm,
                          boolean cashOnDelivery) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(parcel, "parcel");
        Objects.requireNonNull(branchDistanceKm, "branchDistanceKm");

        BigDecimal volumetricKg = parcel.hasDimensions() ? volumetricWeight(card, parcel.dimensions()) : null;
        int billableKg = billableWeight(card, parcel);
        // A card without a base fee (0) gives no BASE line, rather than a line of 0.00; the same for COD below.
        FeeLine base = card.baseFee().signum() > 0
                ? new FeeLine(FeeComponent.BASE, Money.of(card.baseFee()))
                : null;
        FeeLine weight = new FeeLine(FeeComponent.WEIGHT, weightCharge(card, billableKg));
        FeeLine linehaul = new FeeLine(FeeComponent.LINEHAUL,
                Money.of(branchDistanceKm.multiply(card.linehaulPerKm())));
        FeeLine cod = cashOnDelivery && card.codFee().signum() > 0
                ? new FeeLine(FeeComponent.COD, Money.of(card.codFee()))
                : null;

        // Without the size the vehicle isn't known, so neither are the local legs; the rest is.
        Optional<VehicleRate> vehicle = parcel.hasDimensions()
                ? card.vehicles().stream().filter(rate -> rate.canCarry(parcel)).findFirst()
                : Optional.empty();
        if (vehicle.isEmpty()) {
            List<FeeLine> lines = lines(base, weight, null, null, linehaul, cod);
            return parcel.hasDimensions()
                    ? new FeeQuote(QuoteStatus.MANUAL_QUOTE, card.version(), card.currency(), volumetricKg,
                            billableKg, null, branchDistanceKm, lines, null)
                    : new FeeQuote(QuoteStatus.PROVISIONAL, card.version(), card.currency(), null, billableKg, null,
                            branchDistanceKm, lines, sum(lines));
        }

        VehicleRate rate = vehicle.get();
        FeeLine pickup = new FeeLine(FeeComponent.PICKUP, Money.of(rate.legFee()));
        FeeLine delivery = new FeeLine(FeeComponent.DELIVERY, Money.of(rate.legFee()));
        List<FeeLine> lines = lines(base, weight, pickup, delivery, linehaul, cod);
        return new FeeQuote(QuoteStatus.COMPLETE, card.version(), card.currency(), volumetricKg, billableKg,
                rate.vehicle(), branchDistanceKm, lines, sum(lines));
    }

    /** L x W x H / divisor, shown with two decimals. */
    static BigDecimal volumetricWeight(RateCard card, Dimensions dimensions) {
        return BigDecimal.valueOf(dimensions.volumeCm3())
                .divide(BigDecimal.valueOf(card.volumetricDivisor()), 2, RoundingMode.HALF_UP);
    }

    /**
     * The greater of the actual and the volumetric weight, rounded up to a whole kilogram. Each is rounded up from
     * its exact value (ceil(max(a, b)) = max(ceil(a), ceil(b))), so 2.001 kg is billed as 3 kg.
     */
    static int billableWeight(RateCard card, ParcelMeasurements parcel) {
        int actual = parcel.actualWeightKg().setScale(0, RoundingMode.CEILING).intValueExact();
        if (!parcel.hasDimensions()) {
            return actual;
        }
        int volumetric = BigDecimal.valueOf(parcel.dimensions().volumeCm3())
                .divide(BigDecimal.valueOf(card.volumetricDivisor()), 0, RoundingMode.CEILING)
                .intValueExact();
        return Math.max(actual, volumetric);
    }

    /** The per-kg fee for every billable kilogram above the rate card's base kilograms. */
    static BigDecimal weightCharge(RateCard card, int billableKg) {
        int extraKg = Math.max(0, billableKg - card.baseWeightKg());
        return Money.of(card.perKgFee().multiply(BigDecimal.valueOf(extraKg)));
    }

    /** The lines in the order they are shown, leaving out the ones that don't apply (null). */
    private static List<FeeLine> lines(FeeLine... lines) {
        List<FeeLine> present = new ArrayList<>();
        for (FeeLine line : lines) {
            if (line != null) {
                present.add(line);
            }
        }
        return present;
    }

    private static BigDecimal sum(List<FeeLine> lines) {
        return Money.of(lines.stream().map(FeeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
