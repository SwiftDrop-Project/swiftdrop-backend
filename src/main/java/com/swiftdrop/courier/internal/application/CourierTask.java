package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.TaskProblem;
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
 * A parcel that was never taken ("Unable to Collect") records NOT_COLLECTED (PICKUP) or NOT_LOADED (TRANSFER,
 * DELIVERY) and keeps its parcel status.
 *
 * <p>The parcel is <em>completed</em> for the courier when its dispatch item is closed ({@link ItemOutcome#closes}):
 * handed over or not collected (PICKUP); delivered, returned or not taken out (DELIVERY). COLLECTED and FAILED stay
 * open until the handover or the return. This is decided by dispatch, never here.
 *
 * @param taskStatus  the status of the whole task (assignment)
 * @param completed   the courier is done with this parcel in this task (the item is closed)
 * @param assignedAt  when the dispatcher created the task
 * @param updatedAt   when the item last changed
 * @param completedAt when courier work completed the item; null for an open item or one closed only by cancellation
 * @param problem     why the parcel couldn't be collected (NOT_COLLECTED, NOT_LOADED)
 * @param receivedBy  who took the parcel at the branch (handover or return)
 */
public record CourierTask(
        UUID assignmentId,
        UUID parcelId,
        AssignmentType type,
        AssignmentStatus taskStatus,
        ItemOutcome outcome,
        boolean completed,
        District originBranch,
        District destinationBranch,
        Instant assignedAt,
        int sequence,
        Instant updatedAt,
        Instant completedAt,
        TaskProblem problem,
        String receivedBy) {

    static CourierTask of(AssignmentView task, AssignmentItemView item) {
        boolean completed = item.outcome().closes(task.type());
        return new CourierTask(task.id(), item.parcelId(), task.type(), task.status(), item.outcome(), completed,
                task.originBranch(), task.destinationBranch(), task.assignedAt(), item.sequence(), item.updatedAt(),
                completed ? item.closedAt() : null, item.problem(), item.receivedBy());
    }

    /** The task was completed or cancelled. */
    public boolean taskClosed() {
        return taskStatus.isClosed();
    }

    /** Nothing more to do with this parcel in this task: it is completed, or the task was completed or cancelled. */
    public boolean closed() {
        return completed || taskClosed();
    }

    /**
     * The statuses the task's next step moves the parcel to, whatever the parcel's own status; none once the step is
     * closed.
     */
    List<ParcelStatus> stepStatuses() {
        if (closed()) {
            return List.of();
        }
        return switch (type) {
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
    }

    /**
     * The statuses the courier may move the parcel to now, in life-cycle order: the task's next step, if the parcel's
     * own status allows it (it may have been moved by someone else) and, for loading, the parcel is at the task's
     * origin branch.
     */
    public List<ParcelStatus> nextStatuses(ParcelStatus current, District currentBranch) {
        if (current == null) {
            return List.of();
        }
        return stepStatuses().stream()
                .filter(current.next()::contains)
                .filter(target -> atTheRightBranch(target, currentBranch))
                .toList();
    }

    /** Loading (IN_TRANSIT, OUT_FOR_DELIVERY) needs the parcel at the task's origin branch; nothing else does. */
    boolean atTheRightBranch(ParcelStatus target, District currentBranch) {
        if (target != ParcelStatus.IN_TRANSIT && target != ParcelStatus.OUT_FOR_DELIVERY) {
            return true;
        }
        return originBranch != null && originBranch == currentBranch;
    }

    /** The outcome "Unable to Collect" records: NOT_COLLECTED for a pickup, NOT_LOADED for a transfer or delivery. */
    ItemOutcome unableToCollectOutcome() {
        return type == AssignmentType.PICKUP ? ItemOutcome.NOT_COLLECTED : ItemOutcome.NOT_LOADED;
    }

    /** "Unable to Collect" is possible now: the step is open and dispatch allows that outcome next. */
    public boolean canMarkUnableToCollect() {
        return !closed() && outcome.canMoveTo(type, unableToCollectOutcome());
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
