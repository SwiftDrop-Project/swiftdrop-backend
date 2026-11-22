package com.swiftdrop.webhooks.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * POST /merchant/webhooks. Event types are wire names (e.g. parcel.delivered); the service checks them, and the URL
 * format and address (CallbackUrlValidator), with the messages of API.md "Webhooks".
 */
public record CreateWebhookRequest(
        @NotBlank(message = "Enter a full https:// address, like https://shop.example.com/webhooks")
        @Size(max = 2048, message = "Enter a full https:// address, like https://shop.example.com/webhooks")
        String url,
        @NotEmpty(message = "Choose at least one event")
        @Size(max = 7, message = "Choose at most 7 events")
        Set<String> eventTypes) {
}
