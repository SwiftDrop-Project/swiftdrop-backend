package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
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
        List<ParcelStatus> nextStatuses,
        TaskResponse task) {

    /** {@code nextStatuses}: only the next step of the courier's dispatch task (none once it is done). */
    public static CourierDeliveryResponse from(DeliverySnapshot parcel, CourierTask task) {
        return new CourierDeliveryResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(task), ContactResponse.from(parcel.pickup()), ContactResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), task.assignedAt(), parcel.updatedAt(),
                task.nextStatuses(parcel.status()), TaskResponse.from(task));
    }
}
