package com.swiftdrop.tracking;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.List;

/**
 * The public view of one parcel: status, towns and the steps so far, oldest first. No personal data.
 *
 * <p>Contract owned by tracking.
 *
 * @param currentBranch only while AT_BRANCH
 * @param steps         oldest first; empty in {@link TrackingApi#mine} lists
 */
public record ParcelTracking(
        String trackingNumber,
        ParcelStatus status,
        District currentBranch,
        Place from,
        Place to,
        Instant createdAt,
        Instant updatedAt,
        List<Step> steps) {

    public ParcelTracking {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** A town and its district. */
    public record Place(String city, District district) {
    }

    /**
     * One step of the journey.
     *
     * @param branch        only for AT_BRANCH
     * @param failureReason only for FAILED
     */
    public record Step(ParcelStatus status, District branch, FailureReason failureReason, Instant occurredAt) {
    }
}
