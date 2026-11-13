package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    /** GET /merchant/webhooks: active and turned off, newest first. */
    List<WebhookSubscription> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /** The ownership check: empty for another merchant's subscription (the caller answers 404). */
    Optional<WebhookSubscription> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** For the limit of 5 active subscriptions. */
    long countByMerchantIdAndActiveTrue(UUID merchantId);

    /** Fan-out: the merchant's active subscriptions that receive this event type (a wire name). */
    @Query(value = "select * from webhooks.subscriptions where merchant_id = :merchantId and active"
            + " and :eventType = any(event_types)", nativeQuery = true)
    List<WebhookSubscription> findActiveListeningTo(@Param("merchantId") UUID merchantId,
                                                    @Param("eventType") String eventType);
}
