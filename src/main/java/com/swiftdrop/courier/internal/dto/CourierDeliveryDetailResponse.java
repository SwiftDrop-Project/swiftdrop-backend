package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel with its history (newest first, without notes) and outcome. Same fields and privacy rules as
 * {@link CourierDeliveryResponse}, plus {@code history}, {@code proof} (only on the entry of the delivery that
 * delivered it) and {@code failureReason} (only on a failed delivery waiting to be returned). For a history entry
 * ({@code GET …/{parcelId}/history/{assignmentId}}) the history ends when that entry finished.
 */
public record CourierDeliveryDetailResponse(
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
        List<HistoryEntryResponse> history,
        ProofResponse proof,
        FailureReason failureReason,
        TaskResponse task) {

    public static CourierDeliveryDetailResponse from(DeliverySnapshot parcel, List<ParcelStatusEntry> history,
                                                     CourierTask task) {
        ProofResponse proof = ProofResponse.of(parcel, task);
        return new CourierDeliveryDetailResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(task), CourierDeliveryResponse.pickupContact(parcel, task),
                CourierDeliveryResponse.recipientContact(parcel, task),
                PlaceResponse.from(parcel.pickup()), PlaceResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), task.assignedAt(), parcel.updatedAt(),
                task.nextStatuses(parcel.status(), parcel.currentBranch()), task.canMarkUnableToCollect(),
                task.completed(), task.completedAt(),
                history.stream().map(HistoryEntryResponse::from).toList(),
                proof,
                failureReasonFor(parcel, task),
                TaskResponse.from(task));
    }

    /**
     * Why the delivery failed, only on the entry of the delivery that failed and is waiting to be returned (the parcel
     * is FAILED now). Never on a pickup entry or another task's entry, so an older step never shows a later failure.
     * A finished (returned) delivery shows the reason in its history, which ends when that entry finished.
     */
    static FailureReason failureReasonFor(DeliverySnapshot parcel, CourierTask task) {
        return task.type() == AssignmentType.DELIVERY && task.outcome() == ItemOutcome.FAILED
                && parcel.status() == ParcelStatus.FAILED
                ? parcel.failureReason()
                : null;
    }
}
