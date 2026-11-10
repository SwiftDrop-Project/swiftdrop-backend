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
 * <pre>
 * fee = base fee (includes the first base kg)
 *     + per-kg fee x billable kg above the base kg
 *     + the vehicle's surcharge
 *     + max(0, distance - the vehicle's included km) x its per-km fee
 *     + the COD fee, when the courier collects cash
 * </pre>
 *
 * Billable weight is the greater of the actual and the volumetric weight (L x W x H / divisor), rounded up to a
 * whole kilogram. The vehicle is the first, smallest first, that carries the actual weight and fits the parcel.
 */
public class FeeCalculator {

    /**
     * @param distanceKm     the estimated road distance from pickup to drop-off
     * @param cashOnDelivery true when the courier collects cash, which adds the COD fee (the cash itself is not part
     *                       of the fee)
     */
    public FeeQuote quote(RateCard card, ParcelMeasurements parcel, BigDecimal distanceKm, boolean cashOnDelivery) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(parcel, "parcel");
        Objects.requireNonNull(distanceKm, "distanceKm");

        BigDecimal volumetricKg = parcel.hasDimensions() ? volumetricWeight(card, parcel.dimensions()) : null;
        int billableKg = billableWeight(card, parcel);

        List<FeeLine> lines = new ArrayList<>();
        lines.add(new FeeLine(FeeComponent.BASE, Money.of(card.baseFee())));
        lines.add(new FeeLine(FeeComponent.WEIGHT, weightCharge(card, billableKg)));
        FeeLine cod = cashOnDelivery ? new FeeLine(FeeComponent.COD, Money.of(card.codFee())) : null;

        if (!parcel.hasDimensions()) {
            // Without the size, neither the vehicle nor its distance rate is known yet.
            addIfPresent(lines, cod);
            return new FeeQuote(QuoteStatus.PROVISIONAL, card.version(), card.currency(), null, billableKg, null,
                    null, lines, sum(lines));
        }

        Optional<VehicleRate> vehicle = card.vehicles().stream()
                .filter(rate -> rate.canCarry(parcel))
                .findFirst();
        if (vehicle.isEmpty()) {
            addIfPresent(lines, cod);
            return new FeeQuote(QuoteStatus.MANUAL_QUOTE, card.version(), card.currency(), volumetricKg, billableKg,
                    null, null, lines, null);
        }

        VehicleRate rate = vehicle.get();
        lines.add(new FeeLine(FeeComponent.VEHICLE_SURCHARGE, Money.of(rate.surcharge())));
        lines.add(new FeeLine(FeeComponent.DISTANCE, rate.distanceCharge(distanceKm)));
        addIfPresent(lines, cod);
        return new FeeQuote(QuoteStatus.COMPLETE, card.version(), card.currency(), volumetricKg, billableKg,
                rate.vehicle(), distanceKm, lines, sum(lines));
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

    /** The per-kg fee for every billable kilogram above the ones the base fee covers. */
    static BigDecimal weightCharge(RateCard card, int billableKg) {
        int extraKg = Math.max(0, billableKg - card.baseWeightKg());
        return Money.of(card.perKgFee().multiply(BigDecimal.valueOf(extraKg)));
    }

    private static void addIfPresent(List<FeeLine> lines, FeeLine line) {
        if (line != null) {
            lines.add(line);
        }
    }

    private static BigDecimal sum(List<FeeLine> lines) {
        return Money.of(lines.stream().map(FeeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
