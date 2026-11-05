package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel with its history (newest first) and outcome. Same fields as {@link CourierDeliveryResponse}, plus
 * {@code history}, {@code proof} (null until DELIVERED) and {@code failureReason} (only for FAILED).
 */
public record CourierDeliveryDetailResponse(
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
        List<HistoryEntryResponse> history,
        ProofResponse proof,
        FailureReason failureReason) {

    public static CourierDeliveryDetailResponse from(DeliverySnapshot parcel, List<ParcelStatusEntry> history) {
        ProofResponse proof = parcel.status() == ParcelStatus.DELIVERED
                ? new ProofResponse(parcel.proofRecipientName(), parcel.hasProofPhoto())
                : null;
        return new CourierDeliveryDetailResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                Stop.of(parcel.status()), ContactResponse.from(parcel.pickup()), ContactResponse.from(parcel.recipient()),
                parcel.weightKg(), parcel.codAmount(), parcel.instructions(), parcel.assignedAt(), parcel.updatedAt(),
                CourierDeliveryResponse.nextStatuses(parcel.status()),
                history.stream().map(HistoryEntryResponse::from).toList(),
                proof,
                parcel.status() == ParcelStatus.FAILED ? parcel.failureReason() : null);
    }
}
