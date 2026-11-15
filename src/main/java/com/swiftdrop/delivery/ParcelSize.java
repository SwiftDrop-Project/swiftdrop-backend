package com.swiftdrop.delivery;

/**
 * A parcel's packed outside measurements in whole centimetres, and whether it must stay upright. Pricing uses them
 * for the volumetric weight and the vehicle of the local legs.
 *
 * <p>Contract owned by delivery.
 *
 * @param keepUpright the parcel may not lie on its side
 */
public record ParcelSize(int lengthCm, int widthCm, int heightCm, boolean keepUpright) {

    /** @throws IllegalArgumentException when a measurement is 0 or less */
    public ParcelSize {
        if (lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
            throw new IllegalArgumentException("Every measurement must be more than 0 cm");
        }
    }
}
