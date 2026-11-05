package com.swiftdrop.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a parcel changes status. Written to delivery's outbox and relayed to RabbitMQ (exchange
 * {@code parcel.events}, routing key {@code parcel.status.changed}). {@code customerId}, {@code merchantId} and
 * {@code actorId} are identity user ids; {@code actorRole} is the role name (e.g. COURIER). {@code fromStatus} is
 * null for a new parcel.
 *
 * <p>Delivery is at-least-once, so a consumer may get the same event twice, or an older one late: drop an event whose
 * {@code eventId} was already handled, and one whose {@code parcelVersion} is lower than the last one seen.
 */
public record ParcelStatusChanged(
        UUID eventId,
        UUID parcelId,
        long parcelVersion,
        String trackingNumber,
        UUID customerId,
        UUID merchantId,
        ParcelStatus fromStatus,
        ParcelStatus toStatus,
        UUID actorId,
        String actorRole,
        String note,
        String failureReason,
        Instant occurredAt) {

    /** Leaves out the note and the failure reason: free text that may hold personal data. */
    @Override
    public String toString() {
        return "ParcelStatusChanged[eventId=" + eventId + ", parcelId=" + parcelId + ", parcelVersion=" + parcelVersion
                + ", trackingNumber=" + trackingNumber + ", " + fromStatus + " -> " + toStatus
                + ", actorRole=" + actorRole + ", occurredAt=" + occurredAt + "]";
    }
}
