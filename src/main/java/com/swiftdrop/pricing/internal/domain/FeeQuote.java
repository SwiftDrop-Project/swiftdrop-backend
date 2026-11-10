package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An estimated delivery fee with its breakdown.
 *
 * @param volumetricWeightKg L x W x H / divisor, two decimals; null without dimensions
 * @param billableWeightKg   the greater of the actual and the volumetric weight, rounded up to a whole kilogram
 * @param vehicle            the smallest vehicle that takes the parcel; null unless COMPLETE
 * @param distanceKm         the estimated road distance; null unless COMPLETE
 * @param lines              the fee components in the order they are shown
 * @param total              the sum of the lines; null for MANUAL_QUOTE
 */
public record FeeQuote(
        QuoteStatus status,
        int rateCardVersion,
        String currency,
        BigDecimal volumetricWeightKg,
        int billableWeightKg,
        Vehicle vehicle,
        BigDecimal distanceKm,
        List<FeeLine> lines,
        BigDecimal total) {

    public enum QuoteStatus {
        /** Every component is known. */
        COMPLETE,
        /** No dimensions yet: base, weight and service fees only; the vehicle and the distance come with them. */
        PROVISIONAL,
        /** No vehicle carries or fits the parcel: someone has to price it. */
        MANUAL_QUOTE
    }

    public enum FeeComponent {
        BASE,
        WEIGHT,
        VEHICLE_SURCHARGE,
        DISTANCE,
        COD
    }

    /** One line of the breakdown, e.g. WEIGHT 400.00. */
    public record FeeLine(FeeComponent component, BigDecimal amount) {
    }

    public FeeQuote {
        Objects.requireNonNull(status, "status");
        lines = List.copyOf(lines);
    }

    /** The amount of one component, or empty when the quote has no such line. */
    public Optional<BigDecimal> amountOf(FeeComponent component) {
        return lines.stream().filter(line -> line.component() == component).map(FeeLine::amount).findFirst();
    }
}
