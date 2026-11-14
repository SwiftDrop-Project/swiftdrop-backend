package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;

/**
 * One status change of a customer's parcel. {@code branch} is set only on AT_BRANCH entries and
 * {@code failureReason} only on FAILED ones. Who made the change stays inside delivery.
 */
public record DeliveryHistoryEntryResponse(
        ParcelStatus status,
        District branch,
        String note,
        FailureReason failureReason,
        Instant occurredAt) {

    public static DeliveryHistoryEntryResponse from(ParcelStatusEntry entry) {
        return new DeliveryHistoryEntryResponse(entry.status(), entry.branch(), entry.note(), entry.failureReason(),
                entry.occurredAt());
    }

    /** Leaves out the note: free text that may hold personal data. */
    @Override
    public String toString() {
        return "DeliveryHistoryEntryResponse[status=" + status + ", branch=" + branch + ", failureReason="
                + failureReason + ", occurredAt=" + occurredAt + "]";
    }
}
