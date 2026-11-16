package com.swiftdrop.webhooks.internal.dto;

import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

/** A delivery in the admin list: the merchant's fields plus {@code merchantId} (API.md "Webhooks"). */
public record AdminWebhookDeliveryResponse(UUID id, UUID merchantId, UUID subscriptionId, UUID eventId,
                                           String eventType, String trackingNumber, String merchantOrderRef,
                                           DeliveryStatus status, int attempts,
                                           Integer lastStatusCode, String lastError, Instant nextAttemptAt,
                                           Instant createdAt, Instant deliveredAt) {

    /** {@code trackingNumber} and {@code merchantOrderRef} are null for a test ping. */
    public static AdminWebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new AdminWebhookDeliveryResponse(delivery.getId(), delivery.getMerchantId(),
                delivery.getSubscriptionId(), delivery.getEventId(), delivery.eventType().wireName(),
                delivery.getTrackingNumber(), delivery.getMerchantOrderRef(), delivery.getStatus(), delivery.getAttempts(), delivery.getLastStatusCode(), delivery.getLastError(),
                delivery.getNextAttemptAt(), delivery.getCreatedAt(), delivery.getDeliveredAt());
    }
}
