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
 * @param vehicle            the smallest vehicle that takes the parcel on the local legs; null unless COMPLETE
 * @param distanceKm         estimated road distance between the pickup and drop-off district branches (0 inside one
 *                           district)
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
        /** No measurements, so no vehicle: everything but the pickup and delivery legs. */
        PROVISIONAL,
        /** No vehicle carries or fits the parcel for the local legs: it needs a manual quote. */
        MANUAL_QUOTE
    }

    public enum Component {
        /** Includes the first kilogram. */
        BASE,
        /** Billable kilograms above the first. */
        WEIGHT,
        /** Pickup address to the pickup district branch, by the vehicle. */
        PICKUP,
        /** Drop-off district branch to the drop-off address, by the vehicle. */
        DELIVERY,
        /** Between the two district branches, by SwiftDrop's own transport. */
        LINEHAUL,
        /** Cash on delivery handling; only when the rate card has a COD fee (version 3 has none). */
        COD
    }

    /** One line of the breakdown, e.g. LINEHAUL 245.20. */
    public record Line(Component component, BigDecimal amount) {
    }

    public PriceQuote {
        lines = List.copyOf(lines);
    }
}
