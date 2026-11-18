package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;

/**
 * One status change of a parcel. {@code branch} only for AT_BRANCH, {@code failureReason} only for FAILED. Notes are
 * left out: they are written by other people (customers, dispatchers, other couriers) for their own work.
 */
public record HistoryEntryResponse(ParcelStatus status, District branch, FailureReason failureReason,
                                   Instant occurredAt) {

    static HistoryEntryResponse from(ParcelStatusEntry entry) {
        return new HistoryEntryResponse(entry.status(), entry.branch(), entry.failureReason(), entry.occurredAt());
    }
}
