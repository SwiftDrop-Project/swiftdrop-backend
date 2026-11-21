package com.swiftdrop.webhooks.internal.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on what a merchant can make SwiftDrop send (API.md "Webhooks", "Limits"), bound from
 * SWIFTDROP_WEBHOOKS_COOLDOWN_SECONDS and SWIFTDROP_WEBHOOKS_ACTIONS_PER_MINUTE.
 *
 * @param cooldownSeconds  wait between two test pings of one subscription, and after a delivery's last attempt
 *                         before the merchant can retry it (0 turns both off)
 * @param actionsPerMinute test pings and retries one merchant may ask for per minute, all subscriptions together
 */
@Validated
@ConfigurationProperties("swiftdrop.webhooks.limits")
public record WebhookLimitsProperties(
        @Min(value = 0, message = "SWIFTDROP_WEBHOOKS_COOLDOWN_SECONDS can't be negative.")
        @Max(value = 3600, message = "SWIFTDROP_WEBHOOKS_COOLDOWN_SECONDS can be at most 3600.")
        int cooldownSeconds,
        @Min(value = 1, message = "SWIFTDROP_WEBHOOKS_ACTIONS_PER_MINUTE must be at least 1.")
        int actionsPerMinute) {
}
