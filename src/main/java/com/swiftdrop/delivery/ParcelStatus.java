package com.swiftdrop.delivery;

import java.util.Objects;

/**
 * The life cycle of a parcel, and the rule for which status may follow which:
 * {@code CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED}, one step at a time and never
 * backwards. Any status that is not final may also become {@code FAILED}. {@code DELIVERED} and {@code FAILED}
 * are final.
 */
public enum ParcelStatus {
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED;

    /** True for {@code DELIVERED} and {@code FAILED}: the parcel's status can no longer change. */
    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }

    /** True when a parcel in this status may move to {@code next}. */
    public boolean canMoveTo(ParcelStatus next) {
        Objects.requireNonNull(next, "next");
        if (isTerminal()) {
            return false;
        }
        if (next == FAILED) {
            return true;
        }
        // No default branch: adding a new status will not compile until it gets a rule here.
        return switch (this) {
            case CREATED -> next == PICKED_UP;
            case PICKED_UP -> next == IN_TRANSIT;
            case IN_TRANSIT -> next == OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> next == DELIVERED;
            case DELIVERED, FAILED -> false;
        };
    }
}
