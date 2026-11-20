package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant order as the merchant sees it: the response of POST /merchant/orders and the item of "My orders".
 * Same shape as the customer Delivery response plus {@code merchantOrderRef}. {@code currentBranch} is set only while
 * AT_BRANCH, {@code failureReason} only while FAILED, {@code proof} only once DELIVERED; otherwise they are null.
 */
public record MerchantOrderResponse(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        String merchantOrderRef,
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
        Proof proof,
        ParcelSize size,
        String vehicle,
        Integer billableWeightKg) {

    public record Proof(String recipientName, boolean hasPhoto) {

        @Override
        public String toString() {
            return "Proof[hasPhoto=" + hasPhoto + "]";
        }
    }

    public static MerchantOrderResponse from(DeliverySnapshot parcel) {
        Proof proof = parcel.status() == ParcelStatus.DELIVERED
                ? new Proof(parcel.proofRecipientName(), parcel.hasProofPhoto())
                : null;
        return new MerchantOrderResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                parcel.merchantOrderRef(), parcel.pickup(), parcel.recipient(), parcel.weightKg(), parcel.codAmount(),
                parcel.instructions(), parcel.feeAmount(), parcel.currency(), parcel.createdAt(), parcel.updatedAt(),
                parcel.currentBranch(), parcel.failureReason(), proof, parcel.size(), parcel.vehicle(),
                parcel.billableWeightKg());
    }

    @Override
    public String toString() {
        return "MerchantOrderResponse[trackingNumber=" + trackingNumber + ", status=" + status
                + ", merchantOrderRef=" + merchantOrderRef + "]";
    }
}
