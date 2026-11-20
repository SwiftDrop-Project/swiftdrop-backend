package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.pricing.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of GET /admin/pricing/tariff: what one rate card charges, worked out with the same fee calculator as every
 * quote. Read only. The admin's Delivery Rates page.
 *
 * @param weightTiers the weight charge for every billable kg from 1 to the heaviest bookable parcel
 * @param vehicles    smallest first
 * @param distancesKm the distances between branches the sample fees are given for, shortest first (0 = same
 *                    district)
 * @param samples     typical parcels with their fee at each of {@code distancesKm}
 */
public record AdminTariffResponse(
        int rateCardVersion,
        boolean active,
        String currency,
        Instant createdAt,
        BigDecimal baseFee,
        int baseWeightKg,
        BigDecimal perKgFee,
        int volumetricDivisor,
        BigDecimal linehaulPerKm,
        BigDecimal codFee,
        List<WeightTier> weightTiers,
        List<VehicleTariff> vehicles,
        List<BigDecimal> distancesKm,
        List<SampleParcel> samples) {

    public AdminTariffResponse {
        weightTiers = List.copyOf(weightTiers);
        vehicles = List.copyOf(vehicles);
        distancesKm = List.copyOf(distancesKm);
        samples = List.copyOf(samples);
    }

    /** The weight part of the fee for a parcel billed at {@code billableKg}. */
    public record WeightTier(int billableKg, BigDecimal weightCharge) {
    }

    /**
     * One vehicle's tariff.
     *
     * @param legFee   one local leg (pickup address to branch, or branch to drop-off address)
     * @param bothLegs pickup and delivery together: what every parcel on this vehicle pays for the local legs
     */
    public record VehicleTariff(Vehicle vehicle, BigDecimal legFee, BigDecimal bothLegs, int maxLengthCm,
                                int maxWidthCm, int maxHeightCm, BigDecimal maxWeightKg) {
    }

    /**
     * A typical parcel and what it costs.
     *
     * @param key              a stable id, e.g. {@code SMALL_BOX}
     * @param vehicle          the vehicle it travels on; null when no vehicle takes it (then every fee is null)
     * @param billableWeightKg the greater of the actual and the volumetric weight, rounded up
     * @param fees             one per distance, in the order of {@code distancesKm}
     */
    public record SampleParcel(String key, String name, BigDecimal weightKg, int lengthCm, int widthCm, int heightCm,
                               Vehicle vehicle, int billableWeightKg, List<SampleFee> fees) {

        public SampleParcel {
            fees = List.copyOf(fees);
        }
    }

    /** The full fee at one distance between branches; null when no vehicle takes the parcel. */
    public record SampleFee(BigDecimal distanceKm, BigDecimal total) {
    }
}
