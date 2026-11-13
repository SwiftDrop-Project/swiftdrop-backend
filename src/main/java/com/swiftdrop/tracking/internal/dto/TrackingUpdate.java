package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;

/**
 * A live update sent to {@code /topic/parcels/{trackingNumber}} when the parcel changes status. Same privacy rules as
 * {@link TrackingResponse}.
 *
 * @param branch  only for AT_BRANCH: the branch the parcel arrived at
 * @param version the parcel's version after this change; ignore an update whose version isn't newer
 */
public record TrackingUpdate(
        String trackingNumber,
        ParcelStatus status,
        District branch,
        FailureReason failureReason,
        Instant occurredAt,
        long version) {
}
