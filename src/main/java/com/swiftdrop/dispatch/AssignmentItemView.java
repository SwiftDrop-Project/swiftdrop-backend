package com.swiftdrop.dispatch;

import com.swiftdrop.delivery.FailureReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only copy of one parcel within an assignment. The parcel itself (status, contacts) comes from
 * {@code DeliveryApi}.
 *
 * <p>Contains personal data (the note, {@code receivedBy} and {@code recipientName}): never log it.
 * {@link #toString()} leaves it out.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 *
 * @param assignmentId  the assignment this item belongs to
 * @param parcelId      the delivery parcel id
 * @param sequence      the order the dispatcher planned, from 1
 * @param outcome       what happened so far; PENDING until the courier records something
 * @param problem       only for NOT_COLLECTED and NOT_LOADED, else null
 * @param failureReason only for FAILED (and RETURNED after it, kept for the record), else null
 * @param note          the courier's last note on this item, or null
 * @param receivedBy    who at the branch received the parcel (HANDED_OVER, RETURNED), or null
 * @param recipientName who received the parcel (DELIVERED), or null
 * @param updatedAt     when the outcome last changed
 * @param closedAt      when the item was closed ({@link ItemOutcome#closes}), or null while it is open
 */
public record AssignmentItemView(
        UUID assignmentId,
        UUID parcelId,
        int sequence,
        ItemOutcome outcome,
        TaskProblem problem,
        FailureReason failureReason,
        String note,
        String receivedBy,
        String recipientName,
        Instant updatedAt,
        Instant closedAt) {

    /** Whether the item is closed, i.e. the courier is done with this parcel in this assignment. */
    public boolean isClosed() {
        return closedAt != null;
    }

    /** Every field except the note, {@code receivedBy} and {@code recipientName}. */
    @Override
    public String toString() {
        return "AssignmentItemView[assignmentId=" + assignmentId + ", parcelId=" + parcelId
                + ", sequence=" + sequence + ", outcome=" + outcome + ", problem=" + problem
                + ", failureReason=" + failureReason + ", updatedAt=" + updatedAt + ", closedAt=" + closedAt + "]";
    }
}
