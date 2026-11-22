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

    /** For the limit of 5 active subscriptions. Call {@link #lockMerchant} first in the same transaction. */
    long countByMerchantIdAndActiveTrue(UUID merchantId);

    /**
     * Serializes "count, then add or turn on" for one merchant until the transaction ends, so two requests at the
     * same moment can't both pass the limit. A PostgreSQL advisory lock: no table is locked.
     */
    // pg_advisory_xact_lock returns void, which JDBC can't map: wrapped so the query returns a number
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(hashtext(cast(:merchantId as text)))) l",
            nativeQuery = true)
    long lockMerchant(@Param("merchantId") UUID merchantId);

    /** Fan-out: the merchant's active subscriptions that receive this event type (a wire name). */
    @Query(value = "select * from webhooks.subscriptions where merchant_id = :merchantId and active"
            + " and :eventType = any(event_types)", nativeQuery = true)
    List<WebhookSubscription> findActiveListeningTo(@Param("merchantId") UUID merchantId,
                                                    @Param("eventType") String eventType);
}
