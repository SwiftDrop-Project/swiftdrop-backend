package com.swiftdrop.webhooks.internal.dto;

import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

/** A delivery in the admin list: the merchant's fields plus {@code merchantId} (API.md "Webhooks"). */
public record AdminWebhookDeliveryResponse(UUID id, UUID merchantId, UUID subscriptionId, UUID eventId,
                                           String eventType, DeliveryStatus status, int attempts,
                                           Integer lastStatusCode, String lastError, Instant nextAttemptAt,
                                           Instant createdAt, Instant deliveredAt) {

    public static AdminWebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new AdminWebhookDeliveryResponse(delivery.getId(), delivery.getMerchantId(),
                delivery.getSubscriptionId(), delivery.getEventId(), delivery.eventType().wireName(),
                delivery.getStatus(), delivery.getAttempts(), delivery.getLastStatusCode(), delivery.getLastError(),
                delivery.getNextAttemptAt(), delivery.getCreatedAt(), delivery.getDeliveredAt());
    }
}
