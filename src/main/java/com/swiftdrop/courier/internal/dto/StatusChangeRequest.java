package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /courier/deliveries/{parcelId}/status}. Only the simple rules are annotations; the rules that
 * depend on the target status (proof for DELIVERED, reason for FAILED, note for OTHER) are checked by the service.
 */
public record StatusChangeRequest(
        @NotNull(message = "Select the new status.")
        ParcelStatus status,

        @Size(max = 500, message = "Keep the note under 500 characters.")
        String note,

        FailureReason failureReason,

        @Size(max = 100, message = "Enter the name of the person who received the parcel.")
        String recipientName) {
}
