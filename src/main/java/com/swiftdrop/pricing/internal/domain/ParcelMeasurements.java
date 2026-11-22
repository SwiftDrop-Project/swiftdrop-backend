package com.swiftdrop.pricing.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What the pricing engine needs to know about a parcel: its actual weight with the packaging, and, when known, its
 * packed outside measurements and whether it must stay upright.
 *
 * @param actualWeightKg the weight in kg, more than 0
 * @param dimensions     the packed size, or null when the customer hasn't given it (the quote is then provisional)
 * @param keepUpright    true when the parcel may not be turned on its side, so its height must stay its height
 */
public record ParcelMeasurements(BigDecimal actualWeightKg, Dimensions dimensions, boolean keepUpright) {

    /** Outside measurements in whole centimetres. */
    public record Dimensions(int lengthCm, int widthCm, int heightCm) {

        public Dimensions {
            if (lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
                throw new IllegalArgumentException("Every measurement must be more than 0 cm");
            }
        }

        /** Length x width x height in cubic centimetres. */
        public long volumeCm3() {
            return (long) lengthCm * widthCm * heightCm;
        }
    }

    public ParcelMeasurements {
        Objects.requireNonNull(actualWeightKg, "actualWeightKg");
        if (actualWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("The weight must be more than 0 kg");
        }
    }

    public boolean hasDimensions() {
        return dimensions != null;
    }
}
