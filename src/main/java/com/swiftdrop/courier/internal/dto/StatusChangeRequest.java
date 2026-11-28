package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /courier/deliveries/{parcelId}/status}. Only the simple rules are annotations; the rules that
 * depend on the target status (recipient name and proof photo for DELIVERED, reason for FAILED, who received it for
 * AT_BRANCH, note for OTHER) are checked by the service.
 *
 * @param receivedBy   the person at the branch who took the parcel; required for AT_BRANCH (handover or return), not
 *                     allowed otherwise
 * @param proofPhotoId the photo of the signed delivery slip from {@code POST .../{parcelId}/proof}; required for
 *                     DELIVERED, not allowed otherwise
 * @param feeCollected the courier confirms they collected the delivery fee in cash, at this step: only for PICKED_UP
 *                     and DELIVERED. Required (true) when the fee is collected at this step (the parcel's
 *                     {@code deliveryFee.collectOn}); refused when there is nothing to collect here. Never an amount:
 *                     the parcel's own fee is recorded
 */
public record StatusChangeRequest(
        @NotNull(message = "Select the new status.")
        ParcelStatus status,

        @Size(max = 500, message = "Keep the note under 500 characters.")
        String note,

        FailureReason failureReason,

        @Size(max = 100, message = "Enter the name of the person who received the parcel.")
        String recipientName,

        @Size(max = 100, message = "Enter the name of the person at the branch who received the parcel.")
        String receivedBy,
        UUID proofPhotoId,
        Boolean feeCollected) {
}
