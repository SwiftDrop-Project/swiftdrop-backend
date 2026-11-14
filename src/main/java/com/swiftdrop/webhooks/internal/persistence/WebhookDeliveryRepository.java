package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The admin list filters with {@link JpaSpecificationExecutor} (status and merchant are both optional). */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID>,
        JpaSpecificationExecutor<WebhookDelivery> {

    /** One subscription's history, newest first. */
    Page<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    /** GET /merchant/webhooks/{id}/deliveries; the order comes with {@code pageable}. */
    Page<WebhookDelivery> findBySubscriptionId(UUID subscriptionId, Pageable pageable);

    /** GET /merchant/webhooks/{id}/deliveries?status=; the order comes with {@code pageable}. */
    Page<WebhookDelivery> findBySubscriptionIdAndStatus(UUID subscriptionId, DeliveryStatus status,
                                                        Pageable pageable);

    /** The ownership check: empty for another merchant's delivery (the caller answers 404). */
    Optional<WebhookDelivery> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** A test ping still waiting to be sent, if there is one (eventType is the wire name, webhook.ping). */
    Optional<WebhookDelivery> findFirstBySubscriptionIdAndEventTypeAndStatus(UUID subscriptionId, String eventType,
                                                                             DeliveryStatus status);

    /**
     * Retry: a FAILED delivery becomes PENDING, due {@code now}, with a new set of attempts. The check and the change
     * are one statement, so two clicks can't both succeed. The last status code and error stay until the next attempt
     * replaces them. Returns 1 when reset, 0 when the delivery isn't FAILED (any more).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE webhooks.deliveries SET status = 'PENDING', attempts = 0, next_attempt_at = :now,"
            + " updated_at = :now WHERE id = :id AND status = 'FAILED'", nativeQuery = true)
    int resetForRetry(UUID id, Instant now);

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
