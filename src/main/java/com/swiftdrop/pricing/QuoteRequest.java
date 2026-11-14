package com.swiftdrop.pricing;

import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * What a price quote needs: the route, the parcel and the services. The caller has checked the values a person
 * typed (the estimate endpoint, or delivery's booking validation).
 *
 * <p>Contract owned by pricing.
 *
 * @param weightKg      actual weight in kg including the packaging, more than 0
 * @param lengthCm      packed outside length; the three measurements come together or not at all. Without them the
 *                      quote is PROVISIONAL (no vehicle, no distance charge)
 * @param keepUpright   the parcel may not lie on its side
 * @param cashOnDelivery the courier collects cash, which adds the rate card's COD handling fee if it has one
 */
public record QuoteRequest(
        District pickupDistrict,
        District dropOffDistrict,
        BigDecimal weightKg,
        Integer lengthCm,
        Integer widthCm,
        Integer heightCm,
        boolean keepUpright,
        boolean cashOnDelivery) {

    /**
     * @throws NullPointerException     when a district or the weight is missing
     * @throws IllegalArgumentException when only some of the measurements are given
     */
    public QuoteRequest {
        Objects.requireNonNull(pickupDistrict, "pickupDistrict");
        Objects.requireNonNull(dropOffDistrict, "dropOffDistrict");
        Objects.requireNonNull(weightKg, "weightKg");
        long given = Stream.of(lengthCm, widthCm, heightCm).filter(Objects::nonNull).count();
        if (given != 0 && given != 3) {
            throw new IllegalArgumentException("Give all three measurements or none");
        }
    }

    public boolean hasDimensions() {
        return lengthCm != null;
    }
}
