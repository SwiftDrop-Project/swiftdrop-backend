package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One parcel in the dispatcher's queue, with the task it needs next.
 *
 * @param waitingSince         when the parcel was created (pickups) or last changed status (at a branch)
 * @param suggestedType        PICKUP for a new parcel; DELIVERY when it is at its recipient's branch, else TRANSFER
 * @param suggestedOrigin      the branch the task starts from (TRANSFER, DELIVERY), else null
 * @param suggestedDestination the branch the task ends at (PICKUP: the sender's district; TRANSFER: the recipient's
 *                             district), else null
 */
public record QueueParcelResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        PlaceResponse from,
        PlaceResponse to,
        BigDecimal weightKg,
        District currentBranch,
        Instant waitingSince,
        AssignmentType suggestedType,
        District suggestedOrigin,
        District suggestedDestination) {
}
