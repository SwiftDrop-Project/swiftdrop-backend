package com.swiftdrop.pricing.internal.controller;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.dto.FeeEstimateRequest;
import com.swiftdrop.pricing.internal.dto.FeeEstimateResponse;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The endpoint passes the request on and returns the quote's breakdown. Roles (401/403) need Spring Security. */
class PricingControllerTest {

    private final PricingApi pricing = mock(PricingApi.class);
    private final PricingController controller = new PricingController(pricing);

    @Test
    void theEstimateIsTheQuoteWithItsBreakdown() {
        QuoteRequest asked = new QuoteRequest(District.COLOMBO, District.GAMPAHA, new BigDecimal("1.8"), 40, 30, 12,
                false, false);
        when(pricing.quote(asked)).thenReturn(new PriceQuote(PriceQuote.Status.COMPLETE, 1, "LKR",
                new BigDecimal("2.88"), 3, Vehicle.BIKE, new BigDecimal("12.0"),
                List.of(new PriceQuote.Line(PriceQuote.Component.BASE, new BigDecimal("350.00")),
                        new PriceQuote.Line(PriceQuote.Component.WEIGHT, new BigDecimal("400.00")),
                        new PriceQuote.Line(PriceQuote.Component.VEHICLE_SURCHARGE, new BigDecimal("0.00")),
                        new PriceQuote.Line(PriceQuote.Component.DISTANCE, new BigDecimal("210.00"))),
                new BigDecimal("960.00")));

        FeeEstimateResponse response = controller.estimate(new FeeEstimateRequest(District.COLOMBO, District.GAMPAHA,
                new BigDecimal("1.8"), 40, 30, 12, false, false));

        assertThat(response.status()).isEqualTo(PriceQuote.Status.COMPLETE);
        assertThat(response.total()).isEqualByComparingTo("960.00");
        assertThat(response.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(response.lines()).extracting(FeeEstimateResponse.Line::component).containsExactly(
                PriceQuote.Component.BASE, PriceQuote.Component.WEIGHT, PriceQuote.Component.VEHICLE_SURCHARGE,
                PriceQuote.Component.DISTANCE);
    }
}
