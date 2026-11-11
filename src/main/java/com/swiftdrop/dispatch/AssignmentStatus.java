package com.swiftdrop.dispatch;

/**
 * Where a courier's assignment is in its own life cycle. This is not the parcel status: an assignment can be
 * COMPLETED while its parcels are still on their way (handed over at a branch).
 * <ul>
 *   <li>ASSIGNED: created by the dispatcher, nothing recorded yet</li>
 *   <li>IN_PROGRESS: the courier recorded the first item outcome</li>
 *   <li>COMPLETED: the courier finished it after every item was closed ({@link ItemOutcome#closes})</li>
 *   <li>CANCELLED: the dispatcher cancelled it</li>
 * </ul>
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 */
public enum AssignmentStatus {
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /** True for COMPLETED and CANCELLED: nothing more can be recorded on the assignment. */
    public boolean isClosed() {
        return this == COMPLETED || this == CANCELLED;
    }
}
