package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Delivery saves events here (OutboxWriter); the outbox relay reads them through DeliveryOutboxApi. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * DeliveryOutboxApi.fetchUnpublished: the oldest unpublished events, at most {@code limit}. Uses the partial index
     * idx_outbox_events_unpublished (created_at WHERE published_at IS NULL).
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Limit limit);
}
