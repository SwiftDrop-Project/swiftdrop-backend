package com.swiftdrop.webhooks.internal.dto;

import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A subscription plus its signing secret: only the create and rotate-secret responses. The secret is shown this once
 * and is never in {@link #toString()}.
 */
public record WebhookWithSecretResponse(UUID id, String url, List<String> eventTypes, boolean active,
                                        Instant createdAt, Instant updatedAt, String secret) {

    public static WebhookWithSecretResponse from(WebhookSubscription subscription, String plainSecret) {
        return new WebhookWithSecretResponse(subscription.getId(), subscription.getUrl(),
                WebhookResponse.wireNames(subscription), subscription.isActive(), subscription.getCreatedAt(),
                subscription.getUpdatedAt(), plainSecret);
    }

    /** Leaves out the secret. */
    @Override
    public String toString() {
        return "WebhookWithSecretResponse[id=" + id + ", active=" + active + "]";
    }
}
