package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.UUID;

/**
 * A live update sent privately to a parcel's owner (the customer who booked it or the merchant who created it) on
 * {@code /user/queue/parcels}. Unlike {@link TrackingUpdate} it carries the parcel id, so the owner's list can apply it
 * without knowing the tracking number in advance. Same privacy rules: no names, phones, addresses or notes.
 *
 * @param branch  only for AT_BRANCH: the branch the parcel arrived at
 * @param version the parcel's version after this change; ignore an update whose version isn't newer
 */
public record OwnParcelUpdate(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        District branch,
        FailureReason failureReason,
        Instant occurredAt,
        long version) {
}
