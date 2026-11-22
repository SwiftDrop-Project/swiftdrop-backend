package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.UUID;

/**
 * One parcel status change for the dispatchers' live board ({@code /topic/live} and
 * {@code GET /tracking/live/recent}). Only staff see it, but it still carries no names, phones, addresses or notes.
 *
 * @param fromStatus null for a new parcel
 * @param branch     only for AT_BRANCH: the branch the parcel arrived at
 * @param origin     the sender's district, or null if the parcel couldn't be read
 * @param destination the recipient's district, or null if the parcel couldn't be read
 */
public record LiveEvent(
        UUID eventId,
        UUID parcelId,
        String trackingNumber,
        ParcelStatus fromStatus,
        ParcelStatus toStatus,
        District branch,
        District origin,
        District destination,
        FailureReason failureReason,
        String actorRole,
        Instant occurredAt,
        long version) {

    /** True when the change concerns this branch: it starts or ends there, or the parcel arrived there. */
    public boolean concerns(District district) {
        return district == null || district == branch || district == origin || district == destination;
    }
}
