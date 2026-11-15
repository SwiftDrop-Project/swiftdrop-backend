package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin's all-parcels tracking list ({@code GET /admin/tracking/parcels}).
 *
 * @param currentBranch only while AT_BRANCH
 * @param failureReason only while FAILED
 * @param version       the parcel's version; a live update with a higher one is newer
 */
public record IndexedParcelResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        TrackingPlace from,
        TrackingPlace to,
        District currentBranch,
        FailureReason failureReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
