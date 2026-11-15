package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;
import com.swiftdrop.pricing.internal.domain.ParcelMeasurements.Dimensions;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

/**
 * One vehicle of a rate card (pricing.vehicle_rates): what it can take, and the fixed price of one local leg (the
 * pickup address to the branch, or the branch to the drop-off address).
 *
 * @param checkOrder  the smallest vehicle has the lowest; vehicles are tried in this order
 * @param maxWeightKg the heaviest actual weight it carries
 * @param legFee      the price of one local leg, with no distance charge (column {@code surcharge})
 */
public record VehicleRate(
        Vehicle vehicle,
        int checkOrder,
        int maxLengthCm,
        int maxWidthCm,
        int maxHeightCm,
        BigDecimal maxWeightKg,
        BigDecimal legFee) {

    public VehicleRate {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(maxWeightKg, "maxWeightKg");
        Objects.requireNonNull(legFee, "legFee");
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

    private static int[] sorted(int... values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }
}
