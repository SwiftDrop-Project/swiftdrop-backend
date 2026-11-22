package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of assigning a courier to a parcel: the one-parcel task that was created for its next step.
 *
 * @param assignmentId   the new task
 * @param assignmentType PICKUP, TRANSFER or DELIVERY, chosen from the parcel's status and branch
 */
public record ParcelAssignmentResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        UUID assignedCourierId,
        Instant assignedAt,
        UUID assignmentId,
        AssignmentType assignmentType) {
}
