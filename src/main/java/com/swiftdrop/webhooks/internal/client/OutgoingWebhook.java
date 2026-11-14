package com.swiftdrop.webhooks.internal.client;

import java.util.UUID;

/**
 * One signed request, ready to send. No toString on purpose: the URL can hold tokens and the body is the merchant's
 * data, so neither should reach a log.
 *
 * @param timestamp Unix seconds, the value of X-SwiftDrop-Timestamp (part of what was signed)
 * @param signature the X-SwiftDrop-Signature value ("sha256=...")
 * @param body      the exact stored payload; sent as UTF-8 bytes, the same bytes that were signed
 */
public record OutgoingWebhook(String url, UUID eventId, String eventType, long timestamp, String signature,
                              String body) {

    @Override
    public String toString() {
        return "OutgoingWebhook[eventId=" + eventId + ", eventType=" + eventType + "]";
    }
}
