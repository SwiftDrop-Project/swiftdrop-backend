package com.swiftdrop.delivery;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life cycle of a parcel, and which status may follow which. A parcel can pass through several SwiftDrop branches
 * (multi-branch workflow, WP9 requirements):
 * <ul>
 *   <li>CREATED → PICKED_UP → AT_BRANCH (the origin branch)</li>
 *   <li>AT_BRANCH → IN_TRANSIT → AT_BRANCH (another branch), as often as needed</li>
 *   <li>AT_BRANCH → OUT_FOR_DELIVERY → DELIVERED or FAILED</li>
 *   <li>FAILED → AT_BRANCH (the courier brings the parcel back), so FAILED is not final</li>
 * </ul>
 * Only DELIVERED is final. Which branch a parcel is at is {@link DeliverySnapshot#currentBranch()}.
 *
 * <p>The transition rules live here, in the public contract, so delivery and every caller (e.g. courier) use
 * the same rules.
 *
 * <p>Contract owned by delivery; transitions agreed with WP9 (courier).
 */
public enum ParcelStatus {
    CREATED,
    PICKED_UP,
    AT_BRANCH,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED;

    /** The statuses this one may move to next. Empty only for DELIVERED. */
    public Set<ParcelStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(PICKED_UP);
            case PICKED_UP -> EnumSet.of(AT_BRANCH);
            case AT_BRANCH -> EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY);
            case IN_TRANSIT -> EnumSet.of(AT_BRANCH);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, FAILED);
            case FAILED -> EnumSet.of(AT_BRANCH);
            case DELIVERED -> EnumSet.noneOf(ParcelStatus.class);
        };
    }

    /** Whether a parcel in this status may move to {@code target}. False for a null target. */
    public boolean canMoveTo(ParcelStatus target) {
        return target != null && next().contains(target);
    }

    /** True only for DELIVERED: the parcel's journey is over. A FAILED parcel goes back to a branch. */
    public boolean isFinished() {
        return this == DELIVERED;
    }
}
