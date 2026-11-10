package com.swiftdrop.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * An estimated delivery fee with its breakdown, worked out from the active rate card.
 *
 * <p>Contract owned by pricing.
 *
 * @param rateCardVersion    the rate card it was worked out from; a booking keeps it to reproduce the price
 * @param volumetricWeightKg L x W x H / divisor with two decimals; null without measurements
 * @param billableWeightKg   the greater of the actual and the volumetric weight, rounded up to a whole kilogram
 * @param vehicle            the smallest vehicle that takes the parcel; null unless COMPLETE
 * @param distanceKm         estimated road distance; null unless COMPLETE
 * @param lines              the fee components in the order to show them
 * @param total              the sum of the lines; null for MANUAL_QUOTE
 */
public record PriceQuote(
        Status status,
        int rateCardVersion,
        String currency,
        BigDecimal volumetricWeightKg,
        int billableWeightKg,
        Vehicle vehicle,
        BigDecimal distanceKm,
        List<Line> lines,
        BigDecimal total) {

    public enum Status {
        /** Every component is known. */
        COMPLETE,
        /** No measurements: base, weight and service fees only; the vehicle and distance come with them. */
        PROVISIONAL,
        /** No vehicle carries or fits the parcel: it needs a manual quote. */
        MANUAL_QUOTE
    }

    public enum Component {
        BASE,
        WEIGHT,
        VEHICLE_SURCHARGE,
        DISTANCE,
        COD
    }

    /** One line of the breakdown, e.g. DISTANCE 210.00. */
    public record Line(Component component, BigDecimal amount) {
    }

    public PriceQuote {
        lines = List.copyOf(lines);
    }
}
