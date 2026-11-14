package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import java.util.UUID;

/**
 * The dispatch task the parcel is in for this courier.
 *
 * @param branch where the courier hands the parcel over: the destination branch for a pickup or transfer, the
 *               origin branch for a delivery (a failed parcel goes back there); null in history rows
 */
public record TaskResponse(UUID assignmentId, AssignmentType type, ItemOutcome outcome, District branch) {

    public static TaskResponse from(CourierTask task) {
        return new TaskResponse(task.assignmentId(), task.type(), task.outcome(), task.handOverBranch());
    }
}
