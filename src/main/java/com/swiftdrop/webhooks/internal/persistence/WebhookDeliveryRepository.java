package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /** GET /merchant/webhooks/{id}/deliveries: one subscription's history, newest first. */
    Page<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    /**
     * Adds a PENDING delivery, due at {@code now}, unless this event already has one for this subscription.
     * ON CONFLICT keeps a redelivered RabbitMQ message harmless without failing the transaction (a duplicate insert
     * through JPA would mark the whole transaction for rollback). Returns 1 when added, 0 when already there.
     */
    @Modifying
    @Query(value = "INSERT INTO webhooks.deliveries"
            + " (id, event_id, subscription_id, merchant_id, event_type, payload, status, attempts,"
            + " next_attempt_at, created_at, updated_at)"
            + " VALUES (:id, :eventId, :subscriptionId, :merchantId, :eventType, :payload, 'PENDING', 0,"
            + " :now, :now, :now)"
            + " ON CONFLICT (event_id, subscription_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(UUID id, UUID eventId, UUID subscriptionId, UUID merchantId, String eventType, String payload,
                       Instant now);
}
