package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.domain.VehicleRateEntity;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse.SampleParcel;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Delivery Rates tariff, worked out with rate card 5's real numbers and the real fee calculator. */
class AdminTariffServiceTest {

    private static final UUID V5 = UUID.randomUUID();
    private static final UUID V4 = UUID.randomUUID();

    private final RateCardRepository rateCards = mock(RateCardRepository.class);
    private final VehicleRateRepository vehicleRates = mock(VehicleRateRepository.class);
    private final AdminTariffService service = new AdminTariffService(rateCards, vehicleRates);

    @Test
    void weightTiersChargeTheExtraKgAboveTheIncludedOne() {
        givenVersion5Active();

        AdminTariffResponse tariff = service.tariff(null);

        assertThat(tariff.rateCardVersion()).isEqualTo(5);
        assertThat(tariff.active()).isTrue();
        assertThat(tariff.weightTiers()).hasSize(50);
        assertThat(tariff.weightTiers().getFirst().billableKg()).isEqualTo(1);
        assertThat(tariff.weightTiers().getFirst().weightCharge()).isEqualByComparingTo("0.00");
        assertThat(tariff.weightTiers().get(2).weightCharge()).as("3 kg").isEqualByComparingTo("100.00");
        assertThat(tariff.weightTiers().getLast().weightCharge()).as("50 kg").isEqualByComparingTo("2450.00");
    }

    @Test
    void eachVehicleShowsBothLegsTogether() {
        givenVersion5Active();

        AdminTariffResponse tariff = service.tariff(null);

        assertThat(tariff.vehicles()).extracting(AdminTariffResponse.VehicleTariff::vehicle)
                .containsExactly(Vehicle.BIKE, Vehicle.TUK_TUK, Vehicle.LORRY);
        assertThat(tariff.vehicles().get(1).legFee()).isEqualByComparingTo("300.00");
        assertThat(tariff.vehicles().get(1).bothLegs()).isEqualByComparingTo("600.00");
    }

    @Test
    void sampleFeesMatchTheQuoteFormula() {
        givenVersion5Active();

        AdminTariffResponse tariff = service.tariff(null);

        assertThat(tariff.distancesKm()).hasSize(6);
        SampleParcel small = sample(tariff, "SMALL_BOX");
        // 30 x 20 x 15 / 5000 = 1.8 -> 2 kg billable; Bike. 50 (1 extra kg) + 150 + 150 + linehaul.
        assertThat(small.vehicle()).isEqualTo(Vehicle.BIKE);
        assertThat(small.billableWeightKg()).isEqualTo(2);
        assertThat(small.fees().getFirst().total()).as("same district").isEqualByComparingTo("350.00");
        assertThat(small.fees().get(3).distanceKm()).isEqualByComparingTo("100.0");
        assertThat(small.fees().get(3).total()).as("100 km").isEqualByComparingTo("450.00");

        SampleParcel medium = sample(tariff, "MEDIUM_BOX");
        // 50 x 40 x 30 doesn't fit a bike (45 x 35 x 35): Tuk-tuk. 60000 / 5000 = 12 kg billable.
        assertThat(medium.vehicle()).isEqualTo(Vehicle.TUK_TUK);
        assertThat(medium.billableWeightKg()).isEqualTo(12);
        assertThat(medium.fees().getFirst().total()).isEqualByComparingTo("1150.00");

        assertThat(sample(tariff, "BULKY_ITEM").vehicle()).isEqualTo(Vehicle.LORRY);
    }

    @Test
    void aParcelNoVehicleTakesHasNoFees() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.of(card(V5, 5, "0.00", true)));
        // Only a bike in this card: the medium box and bigger fit nothing.
        when(vehicleRates.findByRateCardIdOrderByCheckOrder(V5)).thenReturn(List.of(
                vehicle(V5, Vehicle.BIKE, 1, 45, 35, 35, "10.00", "150.00")));

        SampleParcel medium = sample(service.tariff(null), "MEDIUM_BOX");

        assertThat(medium.vehicle()).isNull();
        assertThat(medium.fees()).allSatisfy(fee -> assertThat(fee.total()).isNull());
    }

    @Test
    void anOlderVersionCanBeShownAndAnUnknownOneIsNotFound() {
        when(rateCards.findByVersion(4)).thenReturn(Optional.of(card(V4, 4, "150.00", false)));
        when(vehicleRates.findByRateCardIdOrderByCheckOrder(V4)).thenReturn(version5Vehicles(V4));
        when(rateCards.findByVersion(99)).thenReturn(Optional.empty());

        AdminTariffResponse old = service.tariff(4);

        assertThat(old.rateCardVersion()).isEqualTo(4);
        assertThat(old.active()).isFalse();
        assertThat(old.baseFee()).isEqualByComparingTo("150.00");
        // The base fee is a separate line, so it is not in the weight charge, but it is in the sample totals.
        assertThat(sample(old, "SMALL_BOX").fees().getFirst().total()).isEqualByComparingTo("500.00");
        assertThatThrownBy(() -> service.tariff(99)).isInstanceOf(NotFoundException.class);
    }

    private void givenVersion5Active() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.of(card(V5, 5, "0.00", true)));
        when(vehicleRates.findByRateCardIdOrderByCheckOrder(V5)).thenReturn(version5Vehicles(V5));
    }

    private static RateCardEntity card(UUID id, int version, String baseFee, boolean active) {
        return new RateCardEntity(id, version, "LKR", new BigDecimal(baseFee), 1, new BigDecimal("50.00"), 5000,
                new BigDecimal("0.00"), new BigDecimal("1.00"), new BigDecimal("1.30"), active,
                Instant.parse("2026-11-14T05:55:00Z"));
    }

    private static List<VehicleRateEntity> version5Vehicles(UUID cardId) {
        return List.of(
                vehicle(cardId, Vehicle.BIKE, 1, 45, 35, 35, "10.00", "150.00"),
                vehicle(cardId, Vehicle.TUK_TUK, 2, 80, 60, 60, "40.00", "300.00"),
                vehicle(cardId, Vehicle.LORRY, 3, 250, 150, 150, "500.00", "1500.00"));
    }

    private static VehicleRateEntity vehicle(UUID cardId, Vehicle vehicle, int order, int length, int width,
                                             int height, String maxKg, String legFee) {
        return new VehicleRateEntity(UUID.randomUUID(), cardId, vehicle, order, length, width, height,
                new BigDecimal(maxKg), new BigDecimal(legFee));
    }

    private static SampleParcel sample(AdminTariffResponse tariff, String key) {
        return tariff.samples().stream().filter(sample -> sample.key().equals(key)).findFirst().orElseThrow();
    }
}
