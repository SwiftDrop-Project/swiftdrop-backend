package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.pricing.internal.domain.FeeCalculator;
import com.swiftdrop.pricing.internal.domain.FeeQuote;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;
import com.swiftdrop.pricing.internal.domain.RateCard;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.pricing.internal.strategy.DistanceCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The pricing module's implementation of {@link PricingApi}: the active rate card from the database, the distance
 * from the {@link DistanceCalculator}, and the fee from the {@link FeeCalculator}.
 */
@Service
class PricingService implements PricingApi {

    private final RateCardRepository rateCards;
    private final VehicleRateRepository vehicleRates;
    private final DistanceCalculator distances;
    private final FeeCalculator calculator = new FeeCalculator();

    PricingService(RateCardRepository rateCards, VehicleRateRepository vehicleRates, DistanceCalculator distances) {
        this.rateCards = rateCards;
        this.vehicleRates = vehicleRates;
        this.distances = distances;
    }

    @Override
    @Transactional(readOnly = true)
    public PriceQuote quote(QuoteRequest request) {
        RateCard card = activeRateCard();
        BigDecimal distanceKm = distances.roadKm(request.pickupDistrict(), request.dropOffDistrict(),
                card.roadFactor());
        FeeQuote fee = calculator.quote(card, measurements(request), distanceKm, request.cashOnDelivery());
        return toPriceQuote(fee);
    }

    /** The rate card quotes use now, with its vehicles. Missing means the database was not set up. */
    private RateCard activeRateCard() {
        RateCardEntity row = rateCards.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active rate card in pricing.rate_cards"));
        return row.toRateCard(vehicleRates.findByRateCardIdOrderByCheckOrder(row.getId()));
    }

    private static ParcelMeasurements measurements(QuoteRequest request) {
        Dimensions dimensions = request.hasDimensions()
                ? new Dimensions(request.lengthCm(), request.widthCm(), request.heightCm())
                : null;
        return new ParcelMeasurements(request.weightKg(), dimensions, request.keepUpright());
    }

    /** The domain result as the public contract; the enums share their names. */
    static PriceQuote toPriceQuote(FeeQuote fee) {
        return new PriceQuote(
                PriceQuote.Status.valueOf(fee.status().name()),
                fee.rateCardVersion(),
                fee.currency(),
                fee.volumetricWeightKg(),
                fee.billableWeightKg(),
                fee.vehicle(),
                fee.distanceKm(),
                fee.lines().stream()
                        .map(line -> new PriceQuote.Line(PriceQuote.Component.valueOf(line.component().name()),
                                line.amount()))
                        .toList(),
                fee.total());
    }
}
