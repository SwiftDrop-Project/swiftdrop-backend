package com.swiftdrop.dispatch;

import com.swiftdrop.delivery.FailureReason;

import java.util.Objects;
import java.util.UUID;

/**
 * Input for recording what happened to one parcel in a courier's assignment ({@link AssignmentApi#recordOutcome}).
 *
 * <p>The caller (WP9 courier) has already checked which fields are present for the outcome: {@code problem} only for
 * NOT_COLLECTED and NOT_LOADED; {@code failureReason} only for FAILED; {@code recipientName} only for DELIVERED;
 * {@code receivedBy} only for HANDED_OVER and RETURNED; a {@code note} when the problem or failure reason is OTHER.
 * Dispatch checks ownership, that the assignment is open and that the step is allowed.
 *
 * <p>Contains personal data (the note, {@code receivedBy} and {@code recipientName}): {@link #toString()} leaves it
 * out.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 *
 * @param assignmentId  the assignment
 * @param parcelId      the parcel within it
 * @param courierId     identity user id of the courier recording it; must be the assignment's courier
 * @param outcome       the new outcome; never PENDING
 * @param problem       only for NOT_COLLECTED and NOT_LOADED
 * @param failureReason only for FAILED
 * @param note          optional free text, max 500 characters
 * @param receivedBy    only for HANDED_OVER and RETURNED: who at the branch received the parcel, max 100
 * @param recipientName only for DELIVERED: who received the parcel, max 100
 */
public record RecordOutcomeCommand(
        UUID assignmentId,
        UUID parcelId,
        UUID courierId,
        ItemOutcome outcome,
        TaskProblem problem,
        FailureReason failureReason,
        String note,
        String receivedBy,
        String recipientName) {

    /**
     * The assignment, parcel, courier and outcome are always needed, and PENDING is only a starting point: a missing
     * one or PENDING is a caller's bug.
     *
     * @throws NullPointerException     when assignmentId, parcelId, courierId or outcome is null
     * @throws IllegalArgumentException when outcome is PENDING
     */
    public RecordOutcomeCommand {
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(courierId, "courierId");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ItemOutcome.PENDING) {
            throw new IllegalArgumentException("PENDING can't be recorded");
        }
    }

    /** Every field except the note, {@code receivedBy} and {@code recipientName}. */
    @Override
    public String toString() {
        return "RecordOutcomeCommand[assignmentId=" + assignmentId + ", parcelId=" + parcelId
                + ", courierId=" + courierId + ", outcome=" + outcome + ", problem=" + problem
                + ", failureReason=" + failureReason + "]";
    }
}
