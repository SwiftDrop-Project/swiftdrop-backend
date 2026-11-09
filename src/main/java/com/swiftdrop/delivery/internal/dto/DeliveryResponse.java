package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A delivery as its customer sees it: the response of POST /deliveries and the item of "My deliveries".
 * {@code currentBranch} is set only while AT_BRANCH, {@code failureReason} only while FAILED, and {@code proof} only
 * once DELIVERED; otherwise they are null.
 */
public record DeliveryResponse(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        DeliveryContact pickup,
        DeliveryContact recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        BigDecimal feeAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        District currentBranch,
        FailureReason failureReason,
        Proof proof) {

    /** Who received a delivered parcel, and whether the courier added a photo. */
    public record Proof(String recipientName, boolean hasPhoto) {

        /** Leaves out the recipient's name: personal data. */
        @Override
        public String toString() {
            return "Proof[hasPhoto=" + hasPhoto + "]";
        }
    }

    public static DeliveryResponse from(DeliverySnapshot parcel) {
        Proof proof = parcel.status() == ParcelStatus.DELIVERED
                ? new Proof(parcel.proofRecipientName(), parcel.hasProofPhoto())
                : null;
        return new DeliveryResponse(parcel.id(), parcel.trackingNumber(), parcel.status(), parcel.pickup(),
                parcel.recipient(), parcel.weightKg(), parcel.codAmount(), parcel.instructions(),
                parcel.feeAmount(), parcel.currency(), parcel.createdAt(), parcel.updatedAt(),
                parcel.currentBranch(), parcel.failureReason(), proof);
    }

    /** The contacts, the instructions and the proof name are personal data: only the tracking number and the status. */
    @Override
    public String toString() {
        return "DeliveryResponse[trackingNumber=" + trackingNumber + ", status=" + status + "]";
    }
}
