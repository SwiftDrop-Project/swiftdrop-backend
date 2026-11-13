package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the body merchants receive (API.md "Webhooks", "What the merchant receives") from the internal event.
 * The internal event is never forwarded as it is: customerId, actorId, actorRole and the free-text note (which may
 * hold personal data) are left out. The record field order is the JSON key order.
 */
@Component
public class WebhookPayloadFactory {

    private final JsonMapper jsonMapper;

    public WebhookPayloadFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** The exact JSON body for a parcel event. {@code merchantOrderRef} may be null. */
    public String build(ParcelStatusChanged event, String merchantOrderRef) {
        ParcelData data = new ParcelData(event.parcelId(), event.trackingNumber(), merchantOrderRef,
                event.toStatus(), event.fromStatus(), event.parcelVersion(), event.branch(), event.failureReason());
        Payload payload = new Payload(event.eventId().toString(),
                WebhookEventType.fromStatus(event.toStatus()).wireName(), event.occurredAt(), data);
        return jsonMapper.writeValueAsString(payload);
    }

    /** {@code id} is the event id (the same on every attempt); {@code createdAt} is when the status changed. */
    record Payload(String id, String type, Instant createdAt, Object data) {
    }

    /** {@code sequence} is the parcel version: merchants ignore an event with a lower one than the last seen. */
    record ParcelData(UUID parcelId, String trackingNumber, String merchantOrderRef, ParcelStatus status,
                      ParcelStatus previousStatus, long sequence, District branch, String failureReason) {
    }
}
