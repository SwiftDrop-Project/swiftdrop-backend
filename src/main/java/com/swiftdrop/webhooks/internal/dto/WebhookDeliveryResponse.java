package com.swiftdrop.webhooks.internal.dto;

import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery as the merchant sees it (API.md "Delivery (response)"). The payload is not included (the merchant got
 * it), and neither is anything the merchant's server answered: only its status code and a short error code.
 */
public record WebhookDeliveryResponse(UUID id, UUID subscriptionId, UUID eventId, String eventType,
                                      DeliveryStatus status, int attempts, Integer lastStatusCode, String lastError,
                                      Instant nextAttemptAt, Instant createdAt, Instant deliveredAt) {

    public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(delivery.getId(), delivery.getSubscriptionId(), delivery.getEventId(),
                delivery.eventType().wireName(), delivery.getStatus(), delivery.getAttempts(),
                delivery.getLastStatusCode(), delivery.getLastError(), delivery.getNextAttemptAt(),
                delivery.getCreatedAt(), delivery.getDeliveredAt());
    }
}
