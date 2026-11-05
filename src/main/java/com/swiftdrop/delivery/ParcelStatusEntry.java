package com.swiftdrop.delivery;

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
 */
public record ParcelStatusEntry(ParcelStatus status, String note, FailureReason failureReason, String actorRole,
                                Instant occurredAt) {

    /** Leaves out the note: free text that may hold personal data. */
    @Override
    public String toString() {
        return "ParcelStatusEntry[status=" + status + ", failureReason=" + failureReason + ", actorRole=" + actorRole
                + ", occurredAt=" + occurredAt + "]";
    }
}
