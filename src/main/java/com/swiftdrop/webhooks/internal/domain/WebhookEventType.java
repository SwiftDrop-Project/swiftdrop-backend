package com.swiftdrop.webhooks.internal.domain;

import com.swiftdrop.delivery.ParcelStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * The webhook event types of API.md "Webhooks": one per parcel status, plus {@code webhook.ping} for test sends.
 * The wire name is what merchants see (payload {@code type}, header {@code X-SwiftDrop-Event-Type}) and what the
 * database stores; the migration's CHECK constraints list the same names.
 */
public enum WebhookEventType {

    PARCEL_CREATED("parcel.created", ParcelStatus.CREATED),
    PARCEL_PICKED_UP("parcel.picked_up", ParcelStatus.PICKED_UP),
    PARCEL_AT_BRANCH("parcel.at_branch", ParcelStatus.AT_BRANCH),
    PARCEL_IN_TRANSIT("parcel.in_transit", ParcelStatus.IN_TRANSIT),
    PARCEL_OUT_FOR_DELIVERY("parcel.out_for_delivery", ParcelStatus.OUT_FOR_DELIVERY),
    PARCEL_DELIVERED("parcel.delivered", ParcelStatus.DELIVERED),
    PARCEL_FAILED("parcel.failed", ParcelStatus.FAILED),
    /** Sent only by POST /merchant/webhooks/{id}/test; can't be subscribed to. */
    WEBHOOK_PING("webhook.ping", null);

    private final String wireName;
    private final ParcelStatus parcelStatus;

    WebhookEventType(String wireName, ParcelStatus parcelStatus) {
        this.wireName = wireName;
        this.parcelStatus = parcelStatus;
    }

    public String wireName() {
        return wireName;
    }

    /** True for the parcel events; false for {@link #WEBHOOK_PING}. */
    public boolean subscribable() {
        return parcelStatus != null;
    }

    /** The event sent when a parcel becomes {@code status}. */
    public static WebhookEventType fromStatus(ParcelStatus status) {
        return Arrays.stream(values())
                .filter(type -> type.parcelStatus == status)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No webhook event for parcel status " + status));
    }

    /** The event with this wire name, e.g. {@code parcel.delivered}. */
    public static Optional<WebhookEventType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }
}
