package com.swiftdrop.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a parcel changes status. Written to delivery's outbox and relayed to RabbitMQ (exchange
 * {@code parcel.events}, routing key {@code parcel.status.changed}). {@code customerId}, {@code merchantId} and
 * {@code actorId} are identity user ids; {@code actorRole} is the role name (e.g. COURIER).
 *
 * <p>Contract owned by delivery - extend in Phase 0.
 */
public record ParcelStatusChanged(
        UUID eventId,
        UUID parcelId,
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
}
