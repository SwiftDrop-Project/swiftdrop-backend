package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel with its history (newest first, without notes) and outcome. Same fields and privacy rules as
 * {@link CourierDeliveryResponse}, plus {@code history}, {@code proof} (null until DELIVERED) and
 * {@code failureReason} (only for FAILED).
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
        ProofResponse proof = parcel.status() == ParcelStatus.DELIVERED
                ? new ProofResponse(parcel.proofRecipientName(), parcel.hasProofPhoto())
                : null;
        return new CourierDeliveryDetailResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(task), CourierDeliveryResponse.pickupContact(parcel, task),
                CourierDeliveryResponse.recipientContact(parcel, task),
                PlaceResponse.from(parcel.pickup()), PlaceResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), task.assignedAt(), parcel.updatedAt(),
                task.nextStatuses(parcel.status(), parcel.currentBranch()), task.canMarkUnableToCollect(),
                task.completed(), task.completedAt(),
                history.stream().map(HistoryEntryResponse::from).toList(),
                proof,
                parcel.status() == ParcelStatus.FAILED ? parcel.failureReason() : null,
                TaskResponse.from(task));
    }
}
