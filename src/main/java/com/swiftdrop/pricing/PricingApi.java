package com.swiftdrop.pricing;

/**
 * What other modules may ask the pricing module. Delivery prices a booking with the same {@link #quote} the estimate
 * endpoint shows, so the customer is charged the fee they saw.
 *
 * <p>Contract owned by pricing.
 */
public interface PricingApi {

    /**
     * The estimated fee and its breakdown from the active rate card: weight, the pickup and delivery legs by vehicle,
     * the distance between the branches, and a base or COD fee when the rate card has one.
     *
     * @throws IllegalStateException when there is no active rate card, or a district has no known location
     */
    PriceQuote quote(QuoteRequest request);
}
