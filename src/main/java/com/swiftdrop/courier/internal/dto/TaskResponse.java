package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.reference.District;

import java.util.UUID;

/**
 * The dispatch task the parcel is in for this courier. The task may group several parcels; the courier works each one
 * on its own.
 *
 * @param status     the whole task's status; it becomes COMPLETED by itself once its last parcel is closed
 * @param outcome    what happened to this parcel in this task so far
 * @param branch     where the courier hands the parcel over: the destination branch for a pickup or transfer, the
 *                   origin branch for a delivery (a failed parcel goes back there)
 * @param problem    why the parcel couldn't be collected (NOT_COLLECTED, NOT_LOADED); otherwise null
 * @param receivedBy who took the parcel at the branch (handover or return); otherwise null
 */
public record TaskResponse(UUID assignmentId, AssignmentType type, AssignmentStatus status,
                           ItemOutcome outcome, District branch, TaskProblem problem, String receivedBy) {

    public static TaskResponse from(CourierTask task) {
        return new TaskResponse(task.assignmentId(), task.type(), task.taskStatus(), task.outcome(),
                task.handOverBranch(), task.problem(), task.receivedBy());
    }
}
