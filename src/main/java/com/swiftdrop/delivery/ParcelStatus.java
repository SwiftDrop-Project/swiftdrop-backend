package com.swiftdrop.delivery;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life cycle of a parcel, and which status may follow which:
 * CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED or FAILED. DELIVERED and FAILED are final.
 *
 * <p>The transition rules live here, in the public contract, so delivery and every caller (e.g. courier) use
 * the same rules.
 *
 * <p>Contract owned by delivery; transitions agreed with WP9 (courier).
 */
public enum ParcelStatus {
    CREATED,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED;

    /** The statuses this one may move to next. Empty for DELIVERED and FAILED. */
    public Set<ParcelStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(PICKED_UP);
            case PICKED_UP -> EnumSet.of(IN_TRANSIT);
            case IN_TRANSIT -> EnumSet.of(OUT_FOR_DELIVERY);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, FAILED);
            case DELIVERED, FAILED -> EnumSet.noneOf(ParcelStatus.class);
        };
    }

    /** Whether a parcel in this status may move to {@code target}. False for a null target. */
    public boolean canMoveTo(ParcelStatus target) {
        return target != null && next().contains(target);
    }

    /** True for DELIVERED and FAILED: the parcel's journey is over. */
    public boolean isFinished() {
        return this == DELIVERED || this == FAILED;
    }
}
