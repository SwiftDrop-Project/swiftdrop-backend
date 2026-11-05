package com.swiftdrop.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * One step in a parcel's status timeline. {@code fromStatus} is null for the first entry (CREATED).
 * {@code actorId} and {@code actorRole} say who made the change (an identity user id and a role name, e.g. COURIER).
 * {@code note}, {@code failureReason} and {@code recipientName} (proof of delivery) are optional.
 */
public record StatusHistoryEntry(
        ParcelStatus fromStatus,
        ParcelStatus toStatus,
        UUID actorId,
        String actorRole,
        String note,
        String failureReason,
        String recipientName,
        Instant occurredAt) {

    /** Leaves out the recipient's name and the free text, so that logging an entry never writes personal data. */
    @Override
    public String toString() {
        return "StatusHistoryEntry[" + fromStatus + " -> " + toStatus + ", actorId=" + actorId
                + ", actorRole=" + actorRole + ", occurredAt=" + occurredAt + "]";
    }
}
