package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PriceQuote.Component;
import com.swiftdrop.pricing.PriceQuote.Line;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.domain.VehicleRateEntity;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.pricing.internal.strategy.DistanceCalculator;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The quote end to end without a database: rate card 2 from the repositories, a fixed branch distance. */
class PricingServiceTest {

    private static final UUID CARD_ID = UUID.randomUUID();
    private static final BigDecimal ROAD_FACTOR = new BigDecimal("1.30");

    private final RateCardRepository rateCards = mock(RateCardRepository.class);
    private final VehicleRateRepository vehicleRates = mock(VehicleRateRepository.class);
    private final DistanceCalculator distances = mock(DistanceCalculator.class);
    private final PricingService pricing = new PricingService(rateCards, vehicleRates, distances);

    @Test
    void colomboToKandyByBikeFromTheActiveRateCard() {
        rateCardTwoIsActive();
        when(distances.roadKm(District.COLOMBO, District.KANDY, ROAD_FACTOR)).thenReturn(new BigDecimal("122.6"));

        PriceQuote quote = pricing.quote(new QuoteRequest(District.COLOMBO, District.KANDY, new BigDecimal("1.8"),
                40, 30, 12, false, false));

        assertThat(quote.status()).isEqualTo(PriceQuote.Status.COMPLETE);
        assertThat(quote.total()).isEqualByComparingTo("1295.20");
        assertThat(quote.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(quote.distanceKm()).isEqualByComparingTo("122.6");
        assertThat(quote.billableWeightKg()).isEqualTo(3);
        assertThat(quote.rateCardVersion()).isEqualTo(2);
        assertThat(quote.currency()).isEqualTo("LKR");
        assertThat(quote.lines()).extracting(Line::component).containsExactly(
                Component.BASE, Component.WEIGHT, Component.PICKUP, Component.DELIVERY, Component.LINEHAUL);
    }

    @Test
    void withoutMeasurementsAndWithCodTheQuoteIsProvisionalButHasTheLinehaul() {
        rateCardTwoIsActive();
        when(distances.roadKm(District.COLOMBO, District.KANDY, ROAD_FACTOR)).thenReturn(new BigDecimal("122.6"));

        PriceQuote quote = pricing.quote(new QuoteRequest(District.COLOMBO, District.KANDY, new BigDecimal("2.4"),
                null, null, null, false, true));

        assertThat(quote.status()).isEqualTo(PriceQuote.Status.PROVISIONAL);
        assertThat(quote.lines()).extracting(Line::component)
                .containsExactly(Component.BASE, Component.WEIGHT, Component.LINEHAUL, Component.COD);
        assertThat(quote.total()).isEqualByComparingTo("1095.20");
        assertThat(quote.vehicle()).isNull();
    }

    @Test
    void noActiveRateCardIsASetupError() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.empty());

        assertThatIllegalStateException().isThrownBy(() -> pricing.quote(new QuoteRequest(District.COLOMBO,
                District.KANDY, new BigDecimal("1"), null, null, null, false, false)));
        verifyNoInteractions(distances);
    }

    private void rateCardTwoIsActive() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.of(new RateCardEntity(CARD_ID, 2, "LKR",
                new BigDecimal("350.00"), 1, new BigDecimal("200.00"), 5000, new BigDecimal("100.00"),
                new BigDecimal("2.00"), ROAD_FACTOR, true, Instant.now())));
        when(vehicleRates.findByRateCardIdOrderByCheckOrder(CARD_ID)).thenReturn(List.of(
                vehicle(Vehicle.BIKE, 1, 45, 35, 35, "10.00", "150.00"),
                vehicle(Vehicle.TUK_TUK, 2, 80, 60, 60, "40.00", "300.00"),
                vehicle(Vehicle.LORRY, 3, 250, 150, 150, "500.00", "1500.00")));
    }

    private static VehicleRateEntity vehicle(Vehicle vehicle, int order, int length, int width, int height,
                                             String maxKg, String legFee) {
        return new VehicleRateEntity(UUID.randomUUID(), CARD_ID, vehicle, order, length, width, height,
                new BigDecimal(maxKg), new BigDecimal(legFee));
    }
}
