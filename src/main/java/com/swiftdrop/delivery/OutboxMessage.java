package com.swiftdrop.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * One event waiting in delivery's outbox, as the outbox relay sees it. {@code eventId} is also the outbox row id.
 * {@code eventType} is the RabbitMQ routing key to publish with (today always {@code parcel.status.changed}), and
 * {@code payload} is the event as JSON (a {@link ParcelStatusChanged}), to be published unchanged.
 */
public record OutboxMessage(
        UUID eventId,
        UUID parcelId,
        String eventType,
        String payload,
        Instant createdAt) {

    /** Leaves out the payload, because its free-text fields may hold personal data. */
    @Override
    public String toString() {
        return "OutboxMessage[eventId=" + eventId + ", parcelId=" + parcelId + ", eventType=" + eventType
                + ", createdAt=" + createdAt + "]";
    }
}
