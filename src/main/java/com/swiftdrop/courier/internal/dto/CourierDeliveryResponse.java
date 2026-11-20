package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel in the courier's lists (API.md "Courier"). Leaves out what the courier doesn't need: customer and
 * merchant ids, the merchant's order reference, the fee and the version.
 *
 * <p>Privacy: the courier gets only the contact they need, and only while the parcel is still theirs to work: the
 * sender ({@code pickup}) in a pickup, the recipient in a delivery, nobody in a transfer; both are null once the
 * parcel is completed or the task closed. {@code from} and {@code to} (city and district) are always there.
 *
 * @param status                 the parcel's own status (delivery); unchanged by "Unable to Collect"
 * @param codAmount              cash to collect in rupees; null when already paid
 * @param nextStatuses           the statuses the courier may set now, in life-cycle order (AT_BRANCH = hand over at,
 *                               or return to, {@code task.branch}); empty once the parcel is completed
 * @param canMarkUnableToCollect "Unable to Collect" is possible now (the parcel hasn't been taken yet)
 * @param completed              the courier is done with this parcel (handed over or not collected for a pickup;
 *                               delivered, returned or not taken out for a delivery), whatever the other parcels of
 *                               the task are doing
 * @param completedAt            when it was completed; null while it is active
 */
public record CourierDeliveryResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        Stop stop,
        ContactResponse pickup,
        ContactResponse recipient,
        PlaceResponse from,
        PlaceResponse to,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        Instant assignedAt,
        Instant updatedAt,
        List<ParcelStatus> nextStatuses,
        boolean canMarkUnableToCollect,
        boolean completed,
        Instant completedAt,
        TaskResponse task) {

    public static CourierDeliveryResponse from(DeliverySnapshot parcel, CourierTask task) {
        return new CourierDeliveryResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(task), pickupContact(parcel, task), recipientContact(parcel, task),
                PlaceResponse.from(parcel.pickup()), PlaceResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), task.assignedAt(), parcel.updatedAt(),
                task.nextStatuses(parcel.status(), parcel.currentBranch()), task.canMarkUnableToCollect(),
                task.completed(), task.completedAt(), TaskResponse.from(task));
    }

    /** The sender, only in an open pickup step. */
    static ContactResponse pickupContact(DeliverySnapshot parcel, CourierTask task) {
        return task.type() == AssignmentType.PICKUP && !task.closed() ? ContactResponse.from(parcel.pickup()) : null;
    }

    /** The recipient, only in an open delivery step. */
    static ContactResponse recipientContact(DeliverySnapshot parcel, CourierTask task) {
        return task.type() == AssignmentType.DELIVERY && !task.closed()
                ? ContactResponse.from(parcel.recipient())
                : null;
    }
}
