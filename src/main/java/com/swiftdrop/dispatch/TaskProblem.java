package com.swiftdrop.dispatch;

/**
 * Why a courier couldn't take a parcel (item outcome NOT_COLLECTED or NOT_LOADED). {@link #OTHER} needs a note that
 * explains what happened. A failed final delivery uses {@link com.swiftdrop.delivery.FailureReason} instead.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 */
public enum TaskProblem {
    /** Nobody was there to hand over the parcel (pickup). */
    SENDER_UNAVAILABLE,
    /** The parcel wasn't packed or ready to go. */
    PARCEL_NOT_READY,
    /** The parcel wasn't there (at the sender or at the branch). */
    PARCEL_MISSING,
    /** The parcel was damaged, so the courier didn't take it. */
    PARCEL_DAMAGED,
    /** The pickup address couldn't be reached (road closed, gated, flooded). */
    ADDRESS_UNREACHABLE,
    /** Anything else; the note says what. */
    OTHER
}
