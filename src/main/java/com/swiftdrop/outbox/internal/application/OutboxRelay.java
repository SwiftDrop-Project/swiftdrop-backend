package com.swiftdrop.outbox.internal.application;

/**
 * Placeholder. Will poll delivery's outbox through {@code com.swiftdrop.delivery.DeliveryOutboxApi} and publish each
 * event to RabbitMQ (exchange {@code parcel.events}), then mark it as sent. Not a Spring bean yet.
 *
 * <p>Owned by outbox - build in Phase 0.
 */
final class OutboxRelay {

    private OutboxRelay() {
    }
}
