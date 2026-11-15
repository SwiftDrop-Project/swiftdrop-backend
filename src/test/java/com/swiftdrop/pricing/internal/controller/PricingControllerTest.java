package com.swiftdrop.pricing.internal.controller;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PriceQuote.Component;
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
        QuoteRequest asked = new QuoteRequest(District.COLOMBO, District.KANDY, new BigDecimal("1.8"), 40, 30, 12,
                false, false);
        when(pricing.quote(asked)).thenReturn(new PriceQuote(PriceQuote.Status.COMPLETE, 2, "LKR",
                new BigDecimal("2.88"), 3, Vehicle.BIKE, new BigDecimal("122.6"),
                List.of(line(Component.BASE, "350.00"), line(Component.WEIGHT, "400.00"),
                        line(Component.PICKUP, "150.00"), line(Component.DELIVERY, "150.00"),
                        line(Component.LINEHAUL, "245.20")),
                new BigDecimal("1295.20")));

        FeeEstimateResponse response = controller.estimate(new FeeEstimateRequest(District.COLOMBO, District.KANDY,
                new BigDecimal("1.8"), 40, 30, 12, false, false));

        assertThat(response.status()).isEqualTo(PriceQuote.Status.COMPLETE);
        assertThat(response.total()).isEqualByComparingTo("1295.20");
        assertThat(response.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(response.distanceKm()).isEqualByComparingTo("122.6");
        assertThat(response.lines()).extracting(FeeEstimateResponse.Line::component).containsExactly(
                Component.BASE, Component.WEIGHT, Component.PICKUP, Component.DELIVERY, Component.LINEHAUL);
    }

    private static PriceQuote.Line line(Component component, String amount) {
        return new PriceQuote.Line(component, new BigDecimal(amount));
    }
}
