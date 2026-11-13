
package com.swiftdrop.infrastructure.messaging;

public final class RabbitMessagingConstants {

    private RabbitMessagingConstants() {
    }

    public static final String PARCEL_EVENTS_EXCHANGE = "parcel.events";
    public static final String PARCEL_STATUS_CHANGED = "parcel.status.changed";

    public static final String NOTIFICATIONS_QUEUE = "notifications.q";
    public static final String WEBHOOKS_QUEUE = "webhooks.q";

    public static final String NOTIFICATIONS_DLX = "notifications.dlx";
    public static final String NOTIFICATIONS_DLQ = "notifications.dlq";
    public static final String NOTIFICATIONS_DEAD_ROUTING_KEY = "notifications.dead";
}
