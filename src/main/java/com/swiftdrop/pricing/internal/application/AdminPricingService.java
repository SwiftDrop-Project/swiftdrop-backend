package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.internal.domain.DistrictLocation;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.domain.VehicleRateEntity;
import com.swiftdrop.pricing.internal.dto.AdminRateCardResponse;
import com.swiftdrop.pricing.internal.dto.AdminRateCardResponse.VehicleLimit;
import com.swiftdrop.pricing.internal.dto.DistrictCentreResponse;
import com.swiftdrop.pricing.internal.dto.RouteTableResponse;
import com.swiftdrop.pricing.internal.dto.RouteTableResponse.Route;
import com.swiftdrop.pricing.internal.persistence.DistrictLocationRepository;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.pricing.internal.strategy.DistanceCalculator;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin's Zones &amp; Pricing page: the active rate card, the district centres and the route table. Read only:
 * nothing here writes to the database (rate cards and centres change only through Flyway migrations). Distances and
 * linehaul come from the same {@link DistanceCalculator} and rate card as every quote, so the page shows exactly what
 * a booking would be charged.
 */
@Service
@Transactional(readOnly = true)
public class AdminPricingService {

    private final RateCardRepository rateCards;
    private final VehicleRateRepository vehicleRates;
    private final DistrictLocationRepository locations;
    private final DistanceCalculator distances;

    public AdminPricingService(RateCardRepository rateCards, VehicleRateRepository vehicleRates,
                               DistrictLocationRepository locations, DistanceCalculator distances) {
        this.rateCards = rateCards;
        this.vehicleRates = vehicleRates;
        this.locations = locations;
        this.distances = distances;
    }

    /** The rate card quotes use now, with its vehicles, smallest first. */
    public AdminRateCardResponse activeRateCard() {
        RateCardEntity card = active();
        return toResponse(card, vehicleRates.findByRateCardIdOrderByCheckOrder(card.getId()));
    }

    /**
     * Every rate card version, newest first, each with its own vehicles. Old versions are kept unchanged because
     * parcels keep the version they were priced with. There are only a handful, so they are read in two queries.
     */
    public List<AdminRateCardResponse> allRateCards() {
        Map<UUID, List<VehicleRateEntity>> vehiclesByCard = vehicleRates.findAll().stream()
                .collect(Collectors.groupingBy(VehicleRateEntity::getRateCardId));
        return rateCards.findAll(Sort.by(Sort.Direction.DESC, "version")).stream()
                .map(card -> toResponse(card, vehiclesByCard.getOrDefault(card.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(VehicleRateEntity::getCheckOrder))
                        .toList()))
                .toList();
    }

    private static AdminRateCardResponse toResponse(RateCardEntity card, List<VehicleRateEntity> vehicles) {
        return new AdminRateCardResponse(card.getVersion(), card.isActive(), card.getCurrency(), card.getBaseFee(),
                card.getBaseWeightKg(), card.getPerKgFee(), card.getVolumetricDivisor(), card.getCodFee(),
                card.getLinehaulPerKm(), card.getRoadFactor(), card.getCreatedAt(),
                vehicles.stream().map(AdminPricingService::toVehicleLimit).toList());
    }

    /** Every district in {@link District} order, with its centre when it has one. */
    public List<DistrictCentreResponse> districts() {
        Map<District, DistrictLocation> centres = centres();
        return Arrays.stream(District.values())
                .map(district -> {
                    DistrictLocation centre = centres.get(district);
                    return centre == null
                            ? new DistrictCentreResponse(district, district.displayName(), false, null, null)
                            : new DistrictCentreResponse(district, district.displayName(), true,
                                    centre.getLatitude(), centre.getLongitude());
                })
                .toList();
    }

    /**
     * The distance and linehaul from {@code from} to every district. A route with a district that has no centre has
     * null distance and linehaul instead of failing the whole table.
     */
    public RouteTableResponse routesFrom(District from) {
        Objects.requireNonNull(from, "from");
        RateCardEntity card = active();
        Map<District, DistrictLocation> centres = centres();
        List<Route> routes = Arrays.stream(District.values())
                .map(to -> route(from, to, card, centres))
                .toList();
        return new RouteTableResponse(from, from.displayName(), card.getVersion(), card.getCurrency(),
                card.getRoadFactor(), card.getLinehaulPerKm(), routes);
    }

    private Route route(District from, District to, RateCardEntity card, Map<District, DistrictLocation> centres) {
        boolean priced = from == to || (centres.containsKey(from) && centres.containsKey(to));
        if (!priced) {
            return new Route(to, to.displayName(), null, null);
        }
        BigDecimal distanceKm = distances.roadKm(from, to, card.getRoadFactor());
        return new Route(to, to.displayName(), distanceKm, linehaul(distanceKm, card.getLinehaulPerKm()));
    }

    /** The same rounding as the fee calculator's LINEHAUL line: two decimals, half up. */
    static BigDecimal linehaul(BigDecimal distanceKm, BigDecimal linehaulPerKm) {
        return distanceKm.multiply(linehaulPerKm).setScale(2, RoundingMode.HALF_UP);
    }

    private RateCardEntity active() {
        return rateCards.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active rate card in pricing.rate_cards"));
    }

    private Map<District, DistrictLocation> centres() {
        return locations.findAll().stream()
                .collect(Collectors.toMap(DistrictLocation::getDistrict, Function.identity(),
                        (first, second) -> first));
    }

    private static VehicleLimit toVehicleLimit(VehicleRateEntity rate) {
        return new VehicleLimit(rate.getVehicle(), rate.getCheckOrder(), rate.getMaxLengthCm(), rate.getMaxWidthCm(),
                rate.getMaxHeightCm(), rate.getMaxWeightKg(), rate.getLegFee());
    }
}
