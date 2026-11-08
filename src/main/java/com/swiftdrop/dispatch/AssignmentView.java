package com.swiftdrop.dispatch;

import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only copy of a courier's assignment and its items.
 *
 * <p>Branch rules per type (a branch is a {@link District}): PICKUP has a destination branch and no origin;
 * TRANSFER has both, and they differ; DELIVERY has an origin branch and no destination (failed parcels go back to the
 * origin).
 *
 * <p>The dispatcher's note may contain personal data: never log it. {@link #toString()} leaves it out.
 *
 * <p>Contract owned by dispatch (WP8); agreed with WP9 (courier).
 *
 * @param id                the assignment id
 * @param courierId         identity user id of the courier doing the work
 * @param type              pickup, transfer or delivery
 * @param status            the assignment's own status (not a parcel status)
 * @param originBranch      where the parcels are taken from (TRANSFER, DELIVERY), else null
 * @param destinationBranch where the parcels are handed over (PICKUP, TRANSFER), else null
 * @param scheduledFor      the Sri Lanka day the work is planned for
 * @param note              the dispatcher's note to the courier, or null
 * @param assignedAt        when the dispatcher created it
 * @param startedAt         when the first item outcome was recorded, or null
 * @param completedAt       when the courier completed it (only for COMPLETED), or null
 * @param cancelledAt       when the dispatcher cancelled it (only for CANCELLED), or null
 * @param items             the parcels, by {@code sequence}; never empty
 * @param version           goes up by one on every change
 */
public record AssignmentView(
        UUID id,
        UUID courierId,
        AssignmentType type,
        AssignmentStatus status,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        String note,
        Instant assignedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        List<AssignmentItemView> items,
        long version) {

    /** Copies {@code items}, so the view can't be changed through the list that built it. */
    public AssignmentView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Every field except the note; items print without their personal data. */
    @Override
    public String toString() {
        return "AssignmentView[id=" + id + ", courierId=" + courierId + ", type=" + type + ", status=" + status
                + ", originBranch=" + originBranch + ", destinationBranch=" + destinationBranch
                + ", scheduledFor=" + scheduledFor + ", assignedAt=" + assignedAt + ", startedAt=" + startedAt
                + ", completedAt=" + completedAt + ", cancelledAt=" + cancelledAt
                + ", items=" + items + ", version=" + version + "]";
    }
}
