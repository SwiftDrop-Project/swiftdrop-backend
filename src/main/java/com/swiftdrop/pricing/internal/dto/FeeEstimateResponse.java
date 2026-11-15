package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.Vehicle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of POST /pricing/estimate: the estimated fee and its breakdown. {@code total} is null when the parcel
 * needs a manual quote; {@code vehicle} and {@code distanceKm} are null unless the status is COMPLETE.
 */
public record FeeEstimateResponse(
        PriceQuote.Status status,
        BigDecimal total,
        String currency,
        int rateCardVersion,
        int billableWeightKg,
        BigDecimal volumetricWeightKg,
        Vehicle vehicle,
        BigDecimal distanceKm,
        List<Line> lines) {

    /** One fee component, e.g. { "component": "WEIGHT", "amount": 400.00 }. */
    public record Line(PriceQuote.Component component, BigDecimal amount) {
    }

    public static FeeEstimateResponse from(PriceQuote quote) {
        return new FeeEstimateResponse(quote.status(), quote.total(), quote.currency(), quote.rateCardVersion(),
                quote.billableWeightKg(), quote.volumetricWeightKg(), quote.vehicle(), quote.distanceKm(),
                quote.lines().stream().map(line -> new Line(line.component(), line.amount())).toList());
    }
}
