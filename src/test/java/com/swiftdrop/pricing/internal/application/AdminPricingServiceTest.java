package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.DistrictLocation;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.domain.VehicleRateEntity;
import com.swiftdrop.pricing.internal.dto.AdminRateCardResponse;
import com.swiftdrop.pricing.internal.dto.DistrictCentreResponse;
import com.swiftdrop.pricing.internal.dto.RouteTableResponse;
import com.swiftdrop.pricing.internal.persistence.DistrictLocationRepository;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.pricing.internal.strategy.DistanceCalculator;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The read-only admin pricing views, with the repositories and the distance strategy mocked. */
class AdminPricingServiceTest {

    private static final UUID CARD_ID = UUID.randomUUID();
    private static final BigDecimal ROAD_FACTOR = new BigDecimal("1.30");
    private static final BigDecimal LINEHAUL_PER_KM = new BigDecimal("1.00");

    private final RateCardRepository rateCards = mock(RateCardRepository.class);
    private final VehicleRateRepository vehicleRates = mock(VehicleRateRepository.class);
    private final DistrictLocationRepository locations = mock(DistrictLocationRepository.class);
    private final DistanceCalculator distances = mock(DistanceCalculator.class);
    private final AdminPricingService service =
            new AdminPricingService(rateCards, vehicleRates, locations, distances);

    @Test
    void showsTheActiveRateCardWithItsVehiclesSmallestFirst() {
        givenActiveCard();
        when(vehicleRates.findByRateCardIdOrderByCheckOrder(CARD_ID)).thenReturn(List.of(
                vehicle(Vehicle.BIKE, 1, 45, 35, 35, "10.00", "150.00"),
                vehicle(Vehicle.TUK_TUK, 2, 80, 60, 60, "40.00", "300.00")));

        AdminRateCardResponse card = service.activeRateCard();

        assertThat(card.version()).isEqualTo(5);
        assertThat(card.active()).isTrue();
        assertThat(card.perKgFee()).isEqualByComparingTo("50.00");
        assertThat(card.roadFactor()).isEqualByComparingTo("1.30");
        assertThat(card.vehicles()).extracting(AdminRateCardResponse.VehicleLimit::vehicle)
                .containsExactly(Vehicle.BIKE, Vehicle.TUK_TUK);
        assertThat(card.vehicles().getFirst().legFee()).isEqualByComparingTo("150.00");
    }

    @Test
    void listsEveryVersionNewestFirstEachWithItsOwnVehicles() {
        UUID oldCardId = UUID.randomUUID();
        RateCardEntity active = new RateCardEntity(CARD_ID, 5, "LKR", new BigDecimal("0.00"), 1,
                new BigDecimal("50.00"), 5000, new BigDecimal("0.00"), LINEHAUL_PER_KM, ROAD_FACTOR, true,
                Instant.parse("2026-11-14T05:55:00Z"));
        RateCardEntity old = new RateCardEntity(oldCardId, 4, "LKR", new BigDecimal("150.00"), 1,
                new BigDecimal("50.00"), 5000, new BigDecimal("0.00"), LINEHAUL_PER_KM, ROAD_FACTOR, false,
                Instant.parse("2026-11-13T20:31:00Z"));
        when(rateCards.findAll(any(Sort.class))).thenReturn(List.of(active, old));
        when(vehicleRates.findAll()).thenReturn(List.of(
                new VehicleRateEntity(UUID.randomUUID(), oldCardId, Vehicle.TUK_TUK, 2, 80, 60, 60,
                        new BigDecimal("40.00"), new BigDecimal("400.00")),
                vehicle(Vehicle.TUK_TUK, 2, 80, 60, 60, "40.00", "300.00"),
                vehicle(Vehicle.BIKE, 1, 45, 35, 35, "10.00", "150.00"),
                new VehicleRateEntity(UUID.randomUUID(), oldCardId, Vehicle.BIKE, 1, 45, 35, 35,
                        new BigDecimal("10.00"), new BigDecimal("200.00"))));

        List<AdminRateCardResponse> cards = service.allRateCards();

        assertThat(cards).extracting(AdminRateCardResponse::version).containsExactly(5, 4);
        assertThat(cards).extracting(AdminRateCardResponse::active).containsExactly(true, false);
        assertThat(cards.get(1).baseFee()).isEqualByComparingTo("150.00");
        assertThat(cards.get(0).vehicles()).extracting(AdminRateCardResponse.VehicleLimit::vehicle)
                .containsExactly(Vehicle.BIKE, Vehicle.TUK_TUK);
        assertThat(cards.get(1).vehicles()).extracting(AdminRateCardResponse.VehicleLimit::legFee)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("200.00"), new BigDecimal("400.00"));
    }

    @Test
    void withoutAnActiveRateCardItFailsLoudly() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.empty());

        assertThatIllegalStateException().isThrownBy(service::activeRateCard);
    }

    @Test
    void listsAllDistrictsAndMarksTheOnesWithoutACentre() {
        when(locations.findAll()).thenReturn(List.of(centre(District.COLOMBO, "6.92710", "79.86120")));

        List<DistrictCentreResponse> districts = service.districts();

        assertThat(districts).hasSize(District.values().length);
        DistrictCentreResponse colombo = districts.stream()
                .filter(row -> row.district() == District.COLOMBO).findFirst().orElseThrow();
        assertThat(colombo.priced()).isTrue();
        assertThat(colombo.name()).isEqualTo("Colombo");
        assertThat(colombo.latitude()).isEqualByComparingTo("6.92710");
        DistrictCentreResponse kandy = districts.stream()
                .filter(row -> row.district() == District.KANDY).findFirst().orElseThrow();
        assertThat(kandy.priced()).isFalse();
        assertThat(kandy.latitude()).isNull();
    }

    @Test
    void theRouteTableUsesTheQuoteDistanceAndRoundsLinehaulLikeAQuote() {
        givenActiveCard();
        when(locations.findAll()).thenReturn(List.of(
                centre(District.COLOMBO, "6.92710", "79.86120"),
                centre(District.KANDY, "7.29060", "80.63370")));
        when(distances.roadKm(eq(District.COLOMBO), any(), eq(ROAD_FACTOR))).thenReturn(new BigDecimal("0.0"));
        when(distances.roadKm(District.COLOMBO, District.KANDY, ROAD_FACTOR)).thenReturn(new BigDecimal("122.6"));

        RouteTableResponse table = service.routesFrom(District.COLOMBO);

        assertThat(table.rateCardVersion()).isEqualTo(5);
        assertThat(table.routes()).hasSize(District.values().length);
        RouteTableResponse.Route kandy = route(table, District.KANDY);
        assertThat(kandy.distanceKm()).isEqualByComparingTo("122.6");
        assertThat(kandy.linehaul()).isEqualByComparingTo("122.60");
        RouteTableResponse.Route colombo = route(table, District.COLOMBO);
        assertThat(colombo.distanceKm()).isEqualByComparingTo("0.0");
        assertThat(colombo.linehaul()).isEqualByComparingTo("0.00");
        RouteTableResponse.Route galle = route(table, District.GALLE);
        assertThat(galle.distanceKm()).as("Galle has no centre in this test").isNull();
        assertThat(galle.linehaul()).isNull();
    }

    @Test
    void linehaulHasTwoDecimalsRoundedHalfUp() {
        assertThat(AdminPricingService.linehaul(new BigDecimal("10.5"), new BigDecimal("1.25")))
                .isEqualByComparingTo("13.13");
        assertThat(AdminPricingService.linehaul(new BigDecimal("0.0"), new BigDecimal("1.00")).scale()).isEqualTo(2);
    }

    private void givenActiveCard() {
        when(rateCards.findByActiveTrue()).thenReturn(Optional.of(new RateCardEntity(CARD_ID, 5, "LKR",
                new BigDecimal("0.00"), 1, new BigDecimal("50.00"), 5000, new BigDecimal("0.00"), LINEHAUL_PER_KM,
                ROAD_FACTOR, true, Instant.parse("2026-11-14T05:55:00Z"))));
    }

    private static VehicleRateEntity vehicle(Vehicle vehicle, int order, int length, int width, int height,
                                             String maxKg, String legFee) {
        return new VehicleRateEntity(UUID.randomUUID(), CARD_ID, vehicle, order, length, width, height,
                new BigDecimal(maxKg), new BigDecimal(legFee));
    }

    private static DistrictLocation centre(District district, String latitude, String longitude) {
        return new DistrictLocation(district, new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private static RouteTableResponse.Route route(RouteTableResponse table, District to) {
        return table.routes().stream().filter(route -> route.to() == to).findFirst().orElseThrow();
    }
}
