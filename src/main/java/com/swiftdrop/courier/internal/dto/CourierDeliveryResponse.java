package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel in the courier's lists (API.md "Courier delivery"). Leaves out what the courier doesn't need:
 * customer and merchant ids, the merchant's order reference, the fee and the version.
 *
 * @param codAmount     cash to collect in rupees; null when already paid
 * @param nextStatuses  what the courier may set now, in life-cycle order; empty once DELIVERED or FAILED
 */
public record CourierDeliveryResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        Stop stop,
        ContactResponse pickup,
        ContactResponse recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        Instant assignedAt,
        Instant updatedAt,
        List<ParcelStatus> nextStatuses) {

    public static CourierDeliveryResponse from(DeliverySnapshot parcel) {
        return new CourierDeliveryResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(parcel.status()), ContactResponse.from(parcel.pickup()), ContactResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), parcel.assignedAt(), parcel.updatedAt(),
                nextStatuses(parcel.status()));
    }

    /** {@link ParcelStatus#next()} in life-cycle order (an EnumSet iterates in declaration order). */
    static List<ParcelStatus> nextStatuses(ParcelStatus status) {
        return List.copyOf(status.next());
    }
}
