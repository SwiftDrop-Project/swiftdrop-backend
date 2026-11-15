package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The dispatch task step a courier has for one parcel: which task (assignment) it is in, what kind, and how far the
 * courier got. It decides which parcel statuses the courier may set next, the task outcome each one records, and the
 * branch an AT_BRANCH change goes to.
 *
 * <p>Steps per task type:
 * <ul>
 *   <li>PICKUP: CREATED → PICKED_UP (collected) → AT_BRANCH at the destination branch (handed over);</li>
 *   <li>TRANSFER: AT_BRANCH → IN_TRANSIT (loaded) → AT_BRANCH at the destination branch (handed over);</li>
 *   <li>DELIVERY: AT_BRANCH → OUT_FOR_DELIVERY (loaded) → DELIVERED, or FAILED → AT_BRANCH back at the origin
 *   branch (returned).</li>
 * </ul>
 *
 * @param closed     the courier has nothing more to do with this parcel in this task (the item is closed, or the
 *                   task was completed or cancelled)
 * @param assignedAt when the dispatcher created the task
 * @param updatedAt  when the item last changed
 */
public record CourierTask(
        UUID assignmentId,
        AssignmentType type,
        ItemOutcome outcome,
        boolean closed,
        District originBranch,
        District destinationBranch,
        Instant assignedAt,
        int sequence,
        Instant updatedAt) {

    static CourierTask of(AssignmentView task, AssignmentItemView item) {
        boolean taskClosed = task.status() == AssignmentStatus.COMPLETED || task.status() == AssignmentStatus.CANCELLED;
        return new CourierTask(task.id(), task.type(), item.outcome(), item.isClosed() || taskClosed,
                task.originBranch(), task.destinationBranch(), task.assignedAt(), item.sequence(), item.updatedAt());
    }

    /** The statuses the courier may move the parcel to now, in life-cycle order; none once the step is closed. */
    public List<ParcelStatus> nextStatuses(ParcelStatus current) {
        if (closed || current == null) {
            return List.of();
        }
        List<ParcelStatus> steps = switch (type) {
            case PICKUP -> switch (outcome) {
                case PENDING -> List.of(ParcelStatus.PICKED_UP);
                case COLLECTED -> List.of(ParcelStatus.AT_BRANCH);
                default -> List.<ParcelStatus>of();
            };
            case TRANSFER -> switch (outcome) {
                case PENDING -> List.of(ParcelStatus.IN_TRANSIT);
                case LOADED -> List.of(ParcelStatus.AT_BRANCH);
                default -> List.<ParcelStatus>of();
            };
            case DELIVERY -> switch (outcome) {
                case PENDING -> List.of(ParcelStatus.OUT_FOR_DELIVERY);
                case LOADED -> List.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED);
                case FAILED -> List.of(ParcelStatus.AT_BRANCH);
                default -> List.<ParcelStatus>of();
            };
        };
        // The parcel's own status must allow it too (it may have been moved by someone else).
        return steps.stream().filter(current.next()::contains).toList();
    }

    /** The task outcome a change to {@code target} records. */
    ItemOutcome outcomeFor(ParcelStatus target) {
        return switch (target) {
            case PICKED_UP -> ItemOutcome.COLLECTED;
            case IN_TRANSIT, OUT_FOR_DELIVERY -> ItemOutcome.LOADED;
            case AT_BRANCH -> type == AssignmentType.DELIVERY ? ItemOutcome.RETURNED : ItemOutcome.HANDED_OVER;
            case DELIVERED -> ItemOutcome.DELIVERED;
            case FAILED -> ItemOutcome.FAILED;
            case CREATED -> throw new IllegalArgumentException("A courier never moves a parcel back to CREATED");
        };
    }

    /** Where the parcel is handed over: the destination branch, or for a delivery that failed, its origin branch. */
    public District handOverBranch() {
        return type == AssignmentType.DELIVERY ? originBranch : destinationBranch;
    }

    /** The branch an AT_BRANCH change goes to; null for every other status. */
    District branchFor(ParcelStatus target) {
        return target == ParcelStatus.AT_BRANCH ? handOverBranch() : null;
    }
}
