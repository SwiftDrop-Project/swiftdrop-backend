package com.swiftdrop.pricing;

/**
 * What other modules may ask the pricing module. Delivery prices a booking with the same {@link #quote} the estimate
 * endpoint shows, so the customer is charged the fee they saw.
 *
 * <p>Contract owned by pricing.
 */
public interface PricingApi {

    /**
     * The estimated fee and its breakdown from the active rate card: base fee, weight, vehicle surcharge, distance
     * and service fees.
     *
     * @throws IllegalStateException when there is no active rate card, or a district has no known location
     */
    PriceQuote quote(QuoteRequest request);
}
