package com.swiftdrop.delivery.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The fee a parcel is booked with and how it was worked out, kept on the parcel so the price can be reproduced.
 *
 * @param vehicle          the local legs' vehicle (a pricing Vehicle name), or null when it wasn't known
 * @param billableWeightKg the billable weight, or null for the old flat fee
 * @param rateCardVersion  the pricing rate card, or null for the old flat fee
 * @param breakdownJson    the fee lines as JSON (e.g. [{"component":"BASE","amount":350.00}, ...]), or null
 */
public record ParcelPrice(
        BigDecimal amount,
        String currency,
        String vehicle,
        Integer billableWeightKg,
        Integer rateCardVersion,
        String breakdownJson) {

    public ParcelPrice {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    /** Only an amount, as before pricing was connected. */
    public static ParcelPrice flat(BigDecimal amount, String currency) {
        return new ParcelPrice(amount, currency, null, null, null, null);
    }
}
