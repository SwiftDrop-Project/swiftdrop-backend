package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.util.Set;

/**
 * What {@link DeliveryApi#search} looks for: every parcel matching all the given criteria. Each criterion is
 * optional (null or empty means "any"). For the admin's All Parcels page (WP4).
 *
 * @param text              part of the tracking number (spaces ignored), the sender's or recipient's name or phone
 *                          (any accepted mobile form), or the merchant's order ref; a full parcel id finds exactly
 *                          that parcel
 * @param statuses          the parcel is in one of these statuses
 * @param pickupDistricts   the pickup address is in one of these districts
 * @param deliveryDistricts the recipient's address is in one of these districts
 * @param createdFrom       booked at or after this moment
 * @param createdBefore     booked before this moment
 * @param cashOnDelivery    true: only parcels with cash to collect; false: only parcels already paid
 * @param sort              the order of the result; null means {@link ParcelSort#NEWEST}
 */
public record ParcelSearch(
        String text,
        Set<ParcelStatus> statuses,
        Set<District> pickupDistricts,
        Set<District> deliveryDistricts,
        Instant createdFrom,
        Instant createdBefore,
        Boolean cashOnDelivery,
        ParcelSort sort) {

    public ParcelSearch {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        pickupDistricts = pickupDistricts == null ? Set.of() : Set.copyOf(pickupDistricts);
        deliveryDistricts = deliveryDistricts == null ? Set.of() : Set.copyOf(deliveryDistricts);
    }

    /** Every parcel, newest first. */
    public static ParcelSearch all() {
        return new ParcelSearch(null, null, null, null, null, null, null, null);
    }

    /** The search text may be a name or phone number: keep it out of logs. */
    @Override
    public String toString() {
        return "ParcelSearch[statuses=" + statuses + ", pickupDistricts=" + pickupDistricts + ", deliveryDistricts="
                + deliveryDistricts + ", createdFrom=" + createdFrom + ", createdBefore=" + createdBefore
                + ", cashOnDelivery=" + cashOnDelivery + ", sort=" + sort + "]";
    }
}
