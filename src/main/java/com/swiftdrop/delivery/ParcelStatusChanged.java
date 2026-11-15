package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a parcel changes status. Written to delivery's outbox and relayed to RabbitMQ (exchange
 * {@code parcel.events}, routing key {@code parcel.status.changed}). {@code customerId}, {@code merchantId} and
 * {@code actorId} are identity user ids; {@code actorRole} is the role name (e.g. COURIER). {@code fromStatus} is
 * null for a new parcel. {@code failureReason} is a {@link FailureReason} name, set only when {@code toStatus} is
 * FAILED. {@code branch} is set only when {@code toStatus} is AT_BRANCH: the branch the parcel arrived at.
 * {@code merchantOrderRef} is the merchant's own order id, on every event of a merchant order that has one; null for
 * a customer's booking. Events written before it was added have no {@code merchantOrderRef} in their JSON, so they
 * read back with null: use {@link DeliveryApi#findById} for those.
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
        Instant occurredAt,
        District branch,
        String merchantOrderRef) {

    /** Without the merchant's order id (null): for a customer's booking, and for code written before it was added. */
    public ParcelStatusChanged(UUID eventId, UUID parcelId, long parcelVersion, String trackingNumber,
                               UUID customerId, UUID merchantId, ParcelStatus fromStatus, ParcelStatus toStatus,
                               UUID actorId, String actorRole, String note, String failureReason,
                               Instant occurredAt, District branch) {
        this(eventId, parcelId, parcelVersion, trackingNumber, customerId, merchantId, fromStatus, toStatus, actorId,
                actorRole, note, failureReason, occurredAt, branch, null);
    }

    /**
     * Leaves out the note: free text that may hold personal data. The failure reason is a fixed code, not free text,
     * so it is shown, and so is the merchant's order id.
     */
    @Override
    public String toString() {
        return "ParcelStatusChanged[eventId=" + eventId + ", parcelId=" + parcelId + ", parcelVersion=" + parcelVersion
                + ", trackingNumber=" + trackingNumber + ", " + fromStatus + " -> " + toStatus
                + ", failureReason=" + failureReason + ", branch=" + branch + ", actorRole=" + actorRole
                + ", merchantOrderRef=" + merchantOrderRef + ", occurredAt=" + occurredAt + "]";
    }
}
