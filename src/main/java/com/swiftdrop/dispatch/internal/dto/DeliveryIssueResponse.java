package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel a courier couldn't collect, load or deliver.
 *
 * @param backInQueue true when the parcel is waiting for a new task now (not in another open task, and CREATED or
 *                    AT_BRANCH), so the dispatcher can plan it again
 */
public record DeliveryIssueResponse(
        UUID assignmentId,
        AssignmentType assignmentType,
        UUID parcelId,
        String trackingNumber,
        ParcelStatus parcelStatus,
        ItemOutcome outcome,
        TaskProblem problem,
        FailureReason failureReason,
        String note,
        CourierRef courier,
        Instant updatedAt,
        boolean backInQueue) {
}
