/**
 * Pricing: the delivery fee estimate from a versioned rate card (base fee, billable weight, the smallest vehicle that
 * takes the parcel, road distance between district centres, COD handling fee).
 *
 * <p>Public API: {@link com.swiftdrop.pricing.PricingApi} with {@link com.swiftdrop.pricing.QuoteRequest},
 * {@link com.swiftdrop.pricing.PriceQuote} and {@link com.swiftdrop.pricing.Vehicle}. Endpoint:
 * {@code POST /pricing/estimate}. Other modules may use only the top-level types of this package.
 *
 * <p>Fee estimate built by Pasindu (WP3 owner, taking this part of WP4). Database schema: pricing (rate_cards,
 * vehicle_rates, district_locations; no foreign keys). Dependencies: shared only. Events published: none. Events
 * consumed: none.
 */
@ApplicationModule(displayName = "Pricing")
package com.swiftdrop.pricing;

import org.springframework.modulith.ApplicationModule;
