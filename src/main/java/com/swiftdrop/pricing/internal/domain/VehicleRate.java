package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

/**
 * One vehicle of a rate card (pricing.vehicle_rates): what it can take and what it costs.
 *
 * @param checkOrder  the smallest vehicle has the lowest; vehicles are tried in this order
 * @param maxWeightKg the heaviest actual weight it carries
 * @param includedKm  kilometres covered by the base fee; each kilometre after them costs {@code perKmFee}
 */
public record VehicleRate(
        Vehicle vehicle,
        int checkOrder,
        int maxLengthCm,
        int maxWidthCm,
        int maxHeightCm,
        BigDecimal maxWeightKg,
        BigDecimal surcharge,
        BigDecimal includedKm,
        BigDecimal perKmFee) {

    public VehicleRate {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(maxWeightKg, "maxWeightKg");
        Objects.requireNonNull(surcharge, "surcharge");
        Objects.requireNonNull(includedKm, "includedKm");
        Objects.requireNonNull(perKmFee, "perKmFee");
    }

    /** It carries the parcel's actual weight and the parcel fits its cargo space. The parcel must have dimensions. */
    public boolean canCarry(ParcelMeasurements parcel) {
        return parcel.actualWeightKg().compareTo(maxWeightKg) <= 0 && fits(parcel.dimensions(), parcel.keepUpright());
    }

    /**
     * An upright parcel keeps its height, but its length and width may swap. Any other parcel may lie in any
     * orientation: it fits when its sides, smallest first, are each no bigger than the cargo space's sides, smallest
     * first.
     */
    boolean fits(Dimensions parcel, boolean keepUpright) {
        if (keepUpright) {
            boolean flatFits = (parcel.lengthCm() <= maxLengthCm && parcel.widthCm() <= maxWidthCm)
                    || (parcel.widthCm() <= maxLengthCm && parcel.lengthCm() <= maxWidthCm);
            return parcel.heightCm() <= maxHeightCm && flatFits;
        }
        int[] sides = sorted(parcel.lengthCm(), parcel.widthCm(), parcel.heightCm());
        int[] space = sorted(maxLengthCm, maxWidthCm, maxHeightCm);
        return sides[0] <= space[0] && sides[1] <= space[1] && sides[2] <= space[2];
    }

    /** max(0, distance - included km) x the per-kilometre fee, in rupees and cents. */
    public BigDecimal distanceCharge(BigDecimal distanceKm) {
        BigDecimal extraKm = distanceKm.subtract(includedKm);
        if (extraKm.signum() <= 0) {
            return Money.ZERO;
        }
        return Money.of(extraKm.multiply(perKmFee));
    }

    private static int[] sorted(int... values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }
}
