package com.swiftdrop.pricing.internal.application;

import com.swiftdrop.pricing.internal.domain.FeeCalculator;
import com.swiftdrop.pricing.internal.domain.FeeQuote;
import com.swiftdrop.pricing.internal.domain.FeeQuote.FeeComponent;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;
import com.swiftdrop.pricing.internal.domain.RateCard;
import com.swiftdrop.pricing.internal.domain.RateCardEntity;
import com.swiftdrop.pricing.internal.domain.VehicleRate;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse.SampleFee;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse.SampleParcel;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse.VehicleTariff;
import com.swiftdrop.pricing.internal.dto.AdminTariffResponse.WeightTier;
import com.swiftdrop.pricing.internal.persistence.RateCardRepository;
import com.swiftdrop.pricing.internal.persistence.VehicleRateRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The admin's Delivery Rates page: what a rate card charges by weight, by vehicle and for typical parcels. Every
 * number comes from the same {@link FeeCalculator} as a real quote, so the page can't drift from what customers pay.
 * Read only: nothing here writes to the database.
 */
@Service
@Transactional(readOnly = true)
public class AdminTariffService {

    /** The heaviest parcel a booking accepts (POST /deliveries and POST /pricing/estimate). */
    static final int MAX_BOOKABLE_KG = 50;

    /** Distances between branches for the sample fees, in km: same district, then nearby to far. */
    static final List<BigDecimal> SAMPLE_DISTANCES_KM = List.of(new BigDecimal("0.0"), new BigDecimal("25.0"),
            new BigDecimal("50.0"), new BigDecimal("100.0"), new BigDecimal("200.0"), new BigDecimal("300.0"));

    /** Typical parcels, smallest first. Their vehicle and billable weight come from the rate card, not from here. */
    static final List<Sample> SAMPLES = List.of(
            new Sample("DOCUMENTS", "Documents", "0.5", 32, 24, 2),
            new Sample("SMALL_BOX", "Small box", "2", 30, 20, 15),
            new Sample("MEDIUM_BOX", "Medium box", "8", 50, 40, 30),
            new Sample("LARGE_BOX", "Large box", "25", 70, 50, 50),
            new Sample("BULKY_ITEM", "Bulky item", "45", 120, 60, 60));

    private final RateCardRepository rateCards;
    private final VehicleRateRepository vehicleRates;
    private final FeeCalculator calculator = new FeeCalculator();

    public AdminTariffService(RateCardRepository rateCards, VehicleRateRepository vehicleRates) {
        this.rateCards = rateCards;
        this.vehicleRates = vehicleRates;
    }

    /**
     * The tariff of rate card {@code version}, or of the active card when it is null.
     *
     * @throws NotFoundException NOT_FOUND when there is no rate card with that version
     */
    public AdminTariffResponse tariff(Integer version) {
        RateCardEntity row = version == null
                ? rateCards.findByActiveTrue()
                        .orElseThrow(() -> new IllegalStateException("No active rate card in pricing.rate_cards"))
                : rateCards.findByVersion(version).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        RateCard card = row.toRateCard(vehicleRates.findByRateCardIdOrderByCheckOrder(row.getId()));

        return new AdminTariffResponse(card.version(), row.isActive(), card.currency(), row.getCreatedAt(),
                card.baseFee(), card.baseWeightKg(), card.perKgFee(), card.volumetricDivisor(), card.linehaulPerKm(),
                card.codFee(), weightTiers(card), vehicles(card), SAMPLE_DISTANCES_KM,
                SAMPLES.stream().map(sample -> sample(card, sample)).toList());
    }

    /** A quote without a size has only the weight-based lines, so its WEIGHT line is the weight charge. */
    private List<WeightTier> weightTiers(RateCard card) {
        return IntStream.rangeClosed(1, MAX_BOOKABLE_KG)
                .mapToObj(kg -> {
                    FeeQuote quote = calculator.quote(card,
                            new ParcelMeasurements(BigDecimal.valueOf(kg), null, false), BigDecimal.ZERO, false);
                    return new WeightTier(kg, quote.amountOf(FeeComponent.WEIGHT).orElse(BigDecimal.ZERO));
                })
                .toList();
    }

    private static List<VehicleTariff> vehicles(RateCard card) {
        return card.vehicles().stream()
                .map(AdminTariffService::toVehicleTariff)
                .toList();
    }

    private static VehicleTariff toVehicleTariff(VehicleRate rate) {
        return new VehicleTariff(rate.vehicle(), rate.legFee(), rate.legFee().add(rate.legFee()), rate.maxLengthCm(),
                rate.maxWidthCm(), rate.maxHeightCm(), rate.maxWeightKg());
    }

    private SampleParcel sample(RateCard card, Sample sample) {
        ParcelMeasurements parcel = new ParcelMeasurements(sample.weightKg(),
                new Dimensions(sample.lengthCm(), sample.widthCm(), sample.heightCm()), false);
        List<FeeQuote> quotes = SAMPLE_DISTANCES_KM.stream()
                .map(distanceKm -> calculator.quote(card, parcel, distanceKm, false))
                .toList();
        FeeQuote first = quotes.getFirst();
        return new SampleParcel(sample.key(), sample.name(), sample.weightKg(), sample.lengthCm(), sample.widthCm(),
                sample.heightCm(), first.vehicle(), first.billableWeightKg(),
                quotes.stream().map(quote -> new SampleFee(quote.distanceKm(), quote.total())).toList());
    }

    /** A typical parcel: name, actual weight and packed size. */
    record Sample(String key, String name, BigDecimal weightKg, int lengthCm, int widthCm, int heightCm) {

        Sample(String key, String name, String weightKg, int lengthCm, int widthCm, int heightCm) {
            this(key, name, new BigDecimal(weightKg), lengthCm, widthCm, heightCm);
        }
    }
}
