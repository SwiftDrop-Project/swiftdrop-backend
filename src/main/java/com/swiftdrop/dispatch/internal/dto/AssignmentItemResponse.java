package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;

import java.time.Instant;
import java.util.UUID;

/**
 * One parcel in a courier task. The parcel fields ({@code trackingNumber}, {@code parcelStatus}, {@code from},
 * {@code to}) are null when delivery doesn't know the parcel any more.
 */
public record AssignmentItemResponse(
        UUID parcelId,
        int sequence,
        String trackingNumber,
        ParcelStatus parcelStatus,
        PlaceResponse from,
        PlaceResponse to,
        ItemOutcome outcome,
        TaskProblem problem,
        FailureReason failureReason,
        String note,
        String receivedBy,
        String recipientName,
        Instant updatedAt,
        Instant closedAt) {
}
