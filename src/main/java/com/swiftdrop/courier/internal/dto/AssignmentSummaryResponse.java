package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One courier task in the lists (API.md "Courier", assignment summary).
 *
 * @param counts      the parcels in the task: total, still open, closed, and with a problem or failed delivery
 * @param canComplete true when the task is open and every parcel in it is closed
 */
public record AssignmentSummaryResponse(
        UUID assignmentId,
        AssignmentType type,
        AssignmentStatus status,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        String note,
        Instant assignedAt,
        Instant completedAt,
        CountsResponse counts,
        boolean canComplete) {

    /** Parcel counts of one task. */
    public record CountsResponse(int total, int open, int closed, int problems) {

        static CountsResponse from(List<AssignmentItemView> items) {
            int closed = (int) items.stream().filter(AssignmentItemView::isClosed).count();
            int problems = (int) items.stream()
                    .filter(item -> item.problem() != null || item.failureReason() != null)
                    .count();
            return new CountsResponse(items.size(), items.size() - closed, closed, problems);
        }
    }

    public static AssignmentSummaryResponse from(AssignmentView assignment) {
        return new AssignmentSummaryResponse(assignment.id(), assignment.type(), assignment.status(),
                assignment.originBranch(), assignment.destinationBranch(), assignment.scheduledFor(),
                assignment.note(), assignment.assignedAt(), assignment.completedAt(),
                CountsResponse.from(assignment.items()), canComplete(assignment));
    }

    static boolean canComplete(AssignmentView assignment) {
        return !assignment.status().isClosed()
                && !assignment.items().isEmpty()
                && assignment.items().stream().allMatch(AssignmentItemView::isClosed);
    }
}
