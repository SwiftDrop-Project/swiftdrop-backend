package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.List;

/**
 * The public view of one parcel ({@code GET /track/{trackingNumber}}): status, towns and the steps so far, oldest
 * first. No names, phones, addresses, notes, amounts or user ids.
 *
 * @param version goes up by one on every change; a live update with a version at or below this one is old
 */
public record TrackingResponse(
        String trackingNumber,
        ParcelStatus status,
        District currentBranch,
        TrackingPlace from,
        TrackingPlace to,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<TrackingStep> history) {
}
