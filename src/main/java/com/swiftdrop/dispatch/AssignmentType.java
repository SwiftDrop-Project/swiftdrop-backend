package com.swiftdrop.dispatch;

/**
 * The kind of work a dispatcher gives a courier. One assignment holds one or more parcels, and a parcel passes through
 * several assignments on its way (multi-branch workflow, WP9 requirements).
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 */
public enum AssignmentType {
    /** Collect parcels from their senders and hand them over at the destination branch. */
    PICKUP,
    /** Take parcels from the origin branch to the destination branch. */
    TRANSFER,
    /** Take parcels from the origin branch to their recipients; failed ones go back to the origin branch. */
    DELIVERY
}
