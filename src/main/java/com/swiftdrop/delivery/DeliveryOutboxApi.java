package com.swiftdrop.delivery;

import java.util.List;
import java.util.UUID;

/**
 * Access to delivery's outbox, for the outbox module's relay only. Delivery writes an outbox row in the same
 * transaction as each parcel change; the relay publishes the rows to RabbitMQ (exchange {@code parcel.events}) and
 * then marks them published.
 *
 * <p>Delivery is at-least-once: a crash between publishing and {@link #markPublished} sends the event again, so
 * consumers drop duplicates by {@code eventId} and older updates by {@code parcelVersion}.
 */
public interface DeliveryOutboxApi {

    /** Up to {@code limit} events that are not published yet, oldest first. {@code limit} must be positive. */
    List<OutboxMessage> fetchUnpublished(int limit);

    /**
     * Marks an event as published. Call it only after RabbitMQ has confirmed the message. Calling it again, or with
     * an unknown id, does nothing.
     */
    void markPublished(UUID eventId);
}
