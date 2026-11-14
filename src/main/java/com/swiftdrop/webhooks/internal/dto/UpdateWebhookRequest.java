package com.swiftdrop.webhooks.internal.dto;

import jakarta.validation.constraints.Size;

import java.util.Set;

/** PATCH /merchant/webhooks/{id}: every field is optional; a missing (null) field stays as it is. */
public record UpdateWebhookRequest(
        @Size(max = 2048, message = "Enter a full https:// address, like https://shop.example.com/webhooks")
        String url,
        @Size(min = 1, message = "Choose at least one event")
        @Size(max = 7, message = "Choose at most 7 events")
        Set<String> eventTypes,
        Boolean active) {
}
