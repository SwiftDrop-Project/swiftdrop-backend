package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rows become the plain records field by field (a swapped width and height would price parcels wrongly). */
class RateCardEntityTest {

    @Test
    void aRateCardRowWithItsVehiclesBecomesTheCalculatorsRateCard() {
        UUID cardId = UUID.randomUUID();
        RateCardEntity row = new RateCardEntity(cardId, 1, "LKR", new BigDecimal("350.00"), 1,
                new BigDecimal("200.00"), 5000, new BigDecimal("100.00"), new BigDecimal("1.30"), true, Instant.now());
        VehicleRateEntity bike = new VehicleRateEntity(UUID.randomUUID(), cardId, Vehicle.BIKE, 1, 45, 35, 30,
                new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("5.0"), new BigDecimal("30.00"));

        RateCard card = row.toRateCard(List.of(bike));

        assertThat(card.version()).isEqualTo(1);
        assertThat(card.currency()).isEqualTo("LKR");
        assertThat(card.baseFee()).isEqualByComparingTo("350");
        assertThat(card.baseWeightKg()).isEqualTo(1);
        assertThat(card.perKgFee()).isEqualByComparingTo("200");
        assertThat(card.volumetricDivisor()).isEqualTo(5000);
        assertThat(card.codFee()).isEqualByComparingTo("100");
        assertThat(card.roadFactor()).isEqualByComparingTo("1.30");
        assertThat(card.vehicles()).containsExactly(new VehicleRate(Vehicle.BIKE, 1, 45, 35, 30,
                new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("5.0"), new BigDecimal("30.00")));
    }
}
