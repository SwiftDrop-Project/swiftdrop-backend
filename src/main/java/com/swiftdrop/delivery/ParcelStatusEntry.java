package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.time.Instant;

/**
 * One line of a parcel's status history ({@link DeliveryApi#history}).
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 *
 * @param status        the status the parcel moved to
 * @param note          free text given with the change, or null
 * @param failureReason only when {@code status} is FAILED, otherwise null
 * @param actorRole     role name of who made the change, e.g. {@code COURIER}
 * @param occurredAt    when the change happened
 * @param branch        only when {@code status} is AT_BRANCH: the branch the parcel arrived at, otherwise null
 */
public record ParcelStatusEntry(ParcelStatus status, String note, FailureReason failureReason, String actorRole,
                                Instant occurredAt, District branch) {

    /** An entry without a branch (any status except AT_BRANCH). Kept so callers written before branches compile. */
    public ParcelStatusEntry(ParcelStatus status, String note, FailureReason failureReason, String actorRole,
                             Instant occurredAt) {
        this(status, note, failureReason, actorRole, occurredAt, null);
    }

    /** Leaves out the note: free text that may hold personal data. */
    @Override
    public String toString() {
        return "ParcelStatusEntry[status=" + status + ", failureReason=" + failureReason + ", actorRole=" + actorRole
                + ", occurredAt=" + occurredAt + ", branch=" + branch + "]";
    }
}
