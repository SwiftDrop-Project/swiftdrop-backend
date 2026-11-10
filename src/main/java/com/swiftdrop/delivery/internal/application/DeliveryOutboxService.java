package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.DeliveryOutboxApi;
import com.swiftdrop.delivery.OutboxMessage;
import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import com.swiftdrop.delivery.internal.persistence.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivery's implementation of {@link DeliveryOutboxApi}, for the outbox module's relay only. The relay fetches the
 * oldest unpublished events, publishes each to RabbitMQ and, once RabbitMQ has confirmed it, marks it published.
 */
@Service
class DeliveryOutboxService implements DeliveryOutboxApi {

    private final OutboxEventRepository events;
    private final Clock clock;

    @Autowired
    DeliveryOutboxService(OutboxEventRepository events) {
        this(events, DeliveryService.DATABASE_CLOCK);
    }

    /** For tests: a fixed clock. */
    DeliveryOutboxService(OutboxEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException when {@code limit} is 0 or less (a bug in the relay) */
    @Override
    @Transactional(readOnly = true)
    public List<OutboxMessage> fetchUnpublished(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        return events.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(limit)).stream()
                .map(OutboxEvent::toMessage)
                .toList();
    }

    /**
     * Idempotent: an event that is already published keeps its first time (OutboxEvent.markPublished), and an unknown
     * id does nothing. The change is saved when the transaction commits.
     */
    @Override
    @Transactional
    public void markPublished(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        events.findById(eventId).ifPresent(event -> event.markPublished(clock.instant()));
    }
}
