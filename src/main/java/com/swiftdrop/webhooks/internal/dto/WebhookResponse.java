package com.swiftdrop.webhooks.internal.dto;

import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A subscription (API.md "Subscription (response)"). Never holds the secret. */
public record WebhookResponse(UUID id, String url, List<String> eventTypes, boolean active, Instant createdAt,
                              Instant updatedAt) {

    public static WebhookResponse from(WebhookSubscription subscription) {
        return new WebhookResponse(subscription.getId(), subscription.getUrl(), wireNames(subscription),
                subscription.isActive(), subscription.getCreatedAt(), subscription.getUpdatedAt());
    }

    /** In the order of the event table (eventTypes() is an EnumSet). */
    static List<String> wireNames(WebhookSubscription subscription) {
        return subscription.eventTypes().stream().map(type -> type.wireName()).toList();
    }
}
