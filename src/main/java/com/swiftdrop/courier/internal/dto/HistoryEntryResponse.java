package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.time.Instant;

/** One status change of a parcel. {@code failureReason} only for FAILED. */
public record HistoryEntryResponse(ParcelStatus status, String note, FailureReason failureReason, Instant occurredAt) {

    static HistoryEntryResponse from(ParcelStatusEntry entry) {
        return new HistoryEntryResponse(entry.status(), entry.note(), entry.failureReason(), entry.occurredAt());
    }
}
