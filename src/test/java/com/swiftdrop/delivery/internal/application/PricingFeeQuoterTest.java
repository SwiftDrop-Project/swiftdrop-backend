package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.internal.domain.ParcelPrice;
import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PriceQuote.Component;
import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A booking is priced with pricing's quote, and the parcel keeps how the fee was worked out. */
class PricingFeeQuoterTest {

    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    private final PricingApi pricing = mock(PricingApi.class);
    private final PricingFeeQuoter quoter = new PricingFeeQuoter(pricing, JsonMapper.builder().build());

    @Test
    void theFeeIsPricingsQuoteWithItsVehicleRateCardAndLines() {
        var booking = booking(new ParcelSize(40, 30, 12, false), null);
        when(pricing.quote(new QuoteRequest(District.COLOMBO, District.KANDY, new BigDecimal("1.80"), 40, 30, 12,
                false, false))).thenReturn(quote(PriceQuote.Status.COMPLETE, Vehicle.BIKE, "1295.20"));

        ParcelPrice price = quoter.quote(booking);

        assertThat(price.amount()).isEqualByComparingTo("1295.20");
        assertThat(price.currency()).isEqualTo("LKR");
        assertThat(price.vehicle()).isEqualTo("BIKE");
        assertThat(price.billableWeightKg()).isEqualTo(3);
        assertThat(price.rateCardVersion()).isEqualTo(2);
        assertThat(price.breakdownJson()).contains("\"component\":\"BASE\"", "\"component\":\"LINEHAUL\"");
    }

    @Test
    void theRouteSizeUprightAndCashGoToPricing() {
        var booking = booking(new ParcelSize(10, 10, 70, true), new BigDecimal("2500.00"));

        assertThat(PricingFeeQuoter.quoteRequest(booking)).isEqualTo(new QuoteRequest(District.COLOMBO,
                District.KANDY, new BigDecimal("1.80"), 10, 10, 70, true, true));
    }

    @Test
    void aBookingWithoutASizeIsRefusedBeforeAskingPricing() {
        // e.g. a caller still using the command without measurements: its quote would have no local legs
        var booking = booking(null, null);

        assertThatThrownBy(() -> quoter.quote(booking)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.fieldErrors()).containsEntry("lengthCm", "Enter the length."));
        verifyNoInteractions(pricing);
    }

    @Test
    void aParcelNoVehicleTakesIsRefused() {
        var booking = booking(new ParcelSize(300, 20, 20, false), null);
        when(pricing.quote(PricingFeeQuoter.quoteRequest(booking)))
                .thenReturn(quote(PriceQuote.Status.MANUAL_QUOTE, null, null));

        assertThatThrownBy(() -> quoter.quote(booking)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.PARCEL_NEEDS_MANUAL_QUOTE));
    }

    private static CreateDeliveryCommand booking(ParcelSize size, BigDecimal codAmount) {
        return new CreateDeliveryCommand(UUID.randomUUID(), null, null, PICKUP, RECIPIENT, new BigDecimal("1.80"),
                codAmount, null, size);
    }

    private static PriceQuote quote(PriceQuote.Status status, Vehicle vehicle, String total) {
        return new PriceQuote(status, 2, "LKR", new BigDecimal("2.88"), 3, vehicle, new BigDecimal("122.6"),
                List.of(new PriceQuote.Line(Component.BASE, new BigDecimal("350.00")),
                        new PriceQuote.Line(Component.LINEHAUL, new BigDecimal("245.20"))),
                total == null ? null : new BigDecimal(total));
    }
}
