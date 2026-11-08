package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;

import java.util.List;

/**
 * Which parcel status goes with each item outcome (API.md "Courier", plan section 4). The courier service records the
 * outcome in dispatch and changes the parcel in delivery in one transaction.
 *
 * <pre>
 * PICKUP    COLLECTED      CREATED          → PICKED_UP
 * PICKUP    HANDED_OVER    PICKED_UP        → AT_BRANCH (destination)
 * TRANSFER  LOADED         AT_BRANCH origin → IN_TRANSIT
 * TRANSFER  HANDED_OVER    IN_TRANSIT       → AT_BRANCH (destination)
 * DELIVERY  LOADED         AT_BRANCH origin → OUT_FOR_DELIVERY
 * DELIVERY  DELIVERED      OUT_FOR_DELIVERY → DELIVERED
 * DELIVERY  FAILED         OUT_FOR_DELIVERY → FAILED
 * DELIVERY  RETURNED       FAILED           → AT_BRANCH (origin)
 * any       NOT_COLLECTED, NOT_LOADED       → no parcel change
 * </pre>
 */
public final class ParcelMoves {

    private ParcelMoves() {
    }

    /**
     * What the courier may record for this item through the outcome endpoint now: {@link ItemOutcome#next} without
     * the handover outcomes. Empty once the assignment is closed. In life-cycle order.
     */
    public static List<ItemOutcome> allowedOutcomes(AssignmentView assignment, AssignmentItemView item) {
        if (assignment.status().isClosed()) {
            return List.of();
        }
        return item.outcome().next(assignment.type()).stream().filter(outcome -> !isHandover(outcome)).toList();
    }

    /**
     * Whether the item can go in a handover now: collected or loaded with a destination branch (PICKUP, TRANSFER),
     * or failed (DELIVERY, back to the origin branch). False once the assignment is closed.
     */
    public static boolean readyForHandover(AssignmentView assignment, AssignmentItemView item) {
        if (assignment.status().isClosed()) {
            return false;
        }
        ItemOutcome handover = handoverOutcome(assignment.type());
        return item.outcome().canMoveTo(assignment.type(), handover)
                && forOutcome(assignment, handover).branch() != null;
    }

    /** The parcel change for one outcome. {@code branch} is set only for AT_BRANCH. */
    record ParcelMove(ParcelStatus status, District branch) {
    }

    /** The parcel change for this outcome in this assignment, or null when the parcel doesn't change. */
    static ParcelMove forOutcome(AssignmentView assignment, ItemOutcome outcome) {
        AssignmentType type = assignment.type();
        return switch (outcome) {
            case COLLECTED -> type == AssignmentType.PICKUP ? move(ParcelStatus.PICKED_UP) : null;
            case LOADED -> switch (type) {
                case TRANSFER -> move(ParcelStatus.IN_TRANSIT);
                case DELIVERY -> move(ParcelStatus.OUT_FOR_DELIVERY);
                case PICKUP -> null;
            };
            case HANDED_OVER -> type == AssignmentType.DELIVERY
                    ? null
                    : new ParcelMove(ParcelStatus.AT_BRANCH, assignment.destinationBranch());
            case DELIVERED -> type == AssignmentType.DELIVERY ? move(ParcelStatus.DELIVERED) : null;
            case FAILED -> type == AssignmentType.DELIVERY ? move(ParcelStatus.FAILED) : null;
            case RETURNED -> type == AssignmentType.DELIVERY
                    ? new ParcelMove(ParcelStatus.AT_BRANCH, assignment.originBranch())
                    : null;
            case PENDING, NOT_COLLECTED, NOT_LOADED -> null;
        };
    }

    /** The outcome that hands parcels over at a branch: RETURNED for a delivery (failed parcels), else HANDED_OVER. */
    static ItemOutcome handoverOutcome(AssignmentType type) {
        return type == AssignmentType.DELIVERY ? ItemOutcome.RETURNED : ItemOutcome.HANDED_OVER;
    }

    /** Outcomes that go through the bulk handover, never through the one-parcel outcome endpoint. */
    static boolean isHandover(ItemOutcome outcome) {
        return outcome == ItemOutcome.HANDED_OVER || outcome == ItemOutcome.RETURNED;
    }

    /**
     * Loading takes the parcel from the assignment's origin branch: the parcel must be AT_BRANCH there. Other moves
     * only need {@link ParcelStatus#canMoveTo}.
     */
    static boolean needsOriginBranch(ItemOutcome outcome) {
        return outcome == ItemOutcome.LOADED;
    }

    private static ParcelMove move(ParcelStatus status) {
        return new ParcelMove(status, null);
    }
}
