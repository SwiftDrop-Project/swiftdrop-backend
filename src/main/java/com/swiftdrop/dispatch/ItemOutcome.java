package com.swiftdrop.dispatch;

import java.util.EnumSet;
import java.util.Set;

/**
 * What happened to one parcel within one assignment, and which outcome may follow which for each
 * {@link AssignmentType}. Every item starts at PENDING.
 * <ul>
 *   <li>PICKUP: PENDING → COLLECTED or NOT_COLLECTED; COLLECTED → HANDED_OVER (at the destination branch)</li>
 *   <li>TRANSFER: PENDING → LOADED or NOT_LOADED; LOADED → HANDED_OVER (at the destination branch)</li>
 *   <li>DELIVERY: PENDING → LOADED or NOT_LOADED; LOADED → DELIVERED or FAILED; FAILED → RETURNED (back at the
 *       origin branch)</li>
 * </ul>
 * An outcome that a type does not use has no next outcome for that type and never closes it.
 *
 * <p>NOT_COLLECTED and NOT_LOADED need a {@link TaskProblem}; FAILED needs a
 * {@link com.swiftdrop.delivery.FailureReason}; DELIVERED needs the recipient's name. The parcel status that goes
 * with each outcome is changed by the caller (WP9 courier) through {@code DeliveryApi}, in the same transaction.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 */
public enum ItemOutcome {
    PENDING,
    COLLECTED,
    LOADED,
    HANDED_OVER,
    DELIVERED,
    FAILED,
    RETURNED,
    NOT_COLLECTED,
    NOT_LOADED;

    /** The outcomes this one may move to next in an assignment of this type. A new set on every call. */
    public Set<ItemOutcome> next(AssignmentType type) {
        return switch (type) {
            case PICKUP -> switch (this) {
                case PENDING -> EnumSet.of(COLLECTED, NOT_COLLECTED);
                case COLLECTED -> EnumSet.of(HANDED_OVER);
                default -> EnumSet.noneOf(ItemOutcome.class);
            };
            case TRANSFER -> switch (this) {
                case PENDING -> EnumSet.of(LOADED, NOT_LOADED);
                case LOADED -> EnumSet.of(HANDED_OVER);
                default -> EnumSet.noneOf(ItemOutcome.class);
            };
            case DELIVERY -> switch (this) {
                case PENDING -> EnumSet.of(LOADED, NOT_LOADED);
                case LOADED -> EnumSet.of(DELIVERED, FAILED);
                case FAILED -> EnumSet.of(RETURNED);
                default -> EnumSet.noneOf(ItemOutcome.class);
            };
        };
    }

    /** Whether an item with this outcome may move to {@code target} in an assignment of this type. */
    public boolean canMoveTo(AssignmentType type, ItemOutcome target) {
        return target != null && next(type).contains(target);
    }

    /**
     * True when the courier is done with the parcel in this type of assignment: it was handed over, delivered,
     * returned, or never taken (NOT_COLLECTED, NOT_LOADED). An assignment can be completed only when every item is
     * closed. A FAILED delivery is not closed: the courier still holds the parcel until it is RETURNED.
     */
    public boolean closes(AssignmentType type) {
        return switch (this) {
            case HANDED_OVER -> type == AssignmentType.PICKUP || type == AssignmentType.TRANSFER;
            case NOT_COLLECTED -> type == AssignmentType.PICKUP;
            case NOT_LOADED -> type == AssignmentType.TRANSFER || type == AssignmentType.DELIVERY;
            case DELIVERED, RETURNED -> type == AssignmentType.DELIVERY;
            case PENDING, COLLECTED, LOADED, FAILED -> false;
        };
    }
}
