package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.ParcelMoves;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel within a courier task (API.md "Courier", assignment item).
 *
 * <p>Personal data only while the courier needs it: {@code contact} is set only while the item and the task are open
 * (the sender for PICKUP, the recipient for DELIVERY, never for TRANSFER). {@code codAmount} and {@code instructions}
 * appear only for DELIVERY.
 *
 * @param allowedOutcomes  what the courier may record now through the outcome endpoint, in life-cycle order
 * @param readyForHandover whether the parcel can be selected for the handover now
 */
public record AssignmentItemResponse(
        UUID parcelId,
        String trackingNumber,
        int sequence,
        ParcelStatus parcelStatus,
        ItemOutcome outcome,
        List<ItemOutcome> allowedOutcomes,
        boolean readyForHandover,
        ContactResponse contact,
        PlaceResponse from,
        PlaceResponse to,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        TaskProblem problem,
        FailureReason failureReason,
        String recipientName,
        String receivedBy,
        String note,
        Instant updatedAt) {

    static AssignmentItemResponse from(AssignmentView assignment, AssignmentItemView item, DeliverySnapshot parcel) {
        boolean delivery = assignment.type() == AssignmentType.DELIVERY;
        boolean open = !item.isClosed() && !assignment.status().isClosed();
        return new AssignmentItemResponse(parcel.id(), parcel.trackingNumber(), item.sequence(), parcel.status(),
                item.outcome(), ParcelMoves.allowedOutcomes(assignment, item),
                ParcelMoves.readyForHandover(assignment, item),
                open ? ContactResponse.from(contactFor(assignment.type(), parcel)) : null,
                PlaceResponse.from(parcel.pickup()), PlaceResponse.from(parcel.recipient()),
                parcel.weightKg(),
                delivery ? parcel.codAmount() : null,
                delivery ? parcel.instructions() : null,
                item.problem(), item.failureReason(), item.recipientName(), item.receivedBy(), item.note(),
                item.updatedAt());
    }

    /** Who the courier meets for this type of task: the sender (PICKUP), the recipient (DELIVERY), or nobody. */
    static DeliveryContact contactFor(AssignmentType type, DeliverySnapshot parcel) {
        return switch (type) {
            case PICKUP -> parcel.pickup();
            case DELIVERY -> parcel.recipient();
            case TRANSFER -> null;
        };
    }
}
