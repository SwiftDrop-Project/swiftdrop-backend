package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /** GET /merchant/webhooks/{id}/deliveries: one subscription's history, newest first. */
    Page<WebhookDelivery> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);
}
