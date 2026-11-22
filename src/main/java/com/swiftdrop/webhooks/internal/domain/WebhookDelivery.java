package com.swiftdrop.webhooks.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One event to send to one subscription (webhooks.deliveries), with its retry state. At most one row per
 * (event, subscription): the unique key makes a redelivered RabbitMQ message add nothing.
 * The payload is the exact body sent on every attempt; no toString on purpose, so it never reaches a log.
 */
@Entity
@Table(name = "deliveries", schema = "webhooks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** Wire name (e.g. parcel.delivered); use {@link #eventType()} instead. */
    @Getter(AccessLevel.NONE)
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** The JSON body, stored as text so the bytes never change between attempts. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /** The parcel this event is about, for search. Null for a test ping. */
    @Column(name = "parcel_id")
    private UUID parcelId;

    /** Normalised (e.g. SD2611004817), for search. Null for a test ping. */
    @Column(name = "tracking_number", length = 20)
    private String trackingNumber;

    /** The merchant's own order number, for search. Null for a test ping or a parcel without one. */
    @Column(name = "merchant_order_ref", length = 64)
    private String merchantOrderRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error", length = 50)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = true;

    /** A new delivery, due at once. */
    public static WebhookDelivery pending(UUID eventId, UUID subscriptionId, UUID merchantId, WebhookEventType type,
                                          String payload, Instant now) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.id = UUID.randomUUID();
        delivery.eventId = eventId;
        delivery.subscriptionId = subscriptionId;
        delivery.merchantId = merchantId;
        delivery.eventType = type.wireName();
        delivery.payload = payload;
        delivery.status = DeliveryStatus.PENDING;
        delivery.attempts = 0;
        delivery.nextAttemptAt = now;
        delivery.createdAt = now;
        delivery.updatedAt = now;
        return delivery;
    }

    /**
     * Records which parcel a new parcel-event delivery is about (search uses these). Parcel deliveries are normally
     * stored by the fan-out's insert, which sets them directly; this is for code that saves the entity itself.
     */
    public WebhookDelivery forParcel(UUID parcelId, String trackingNumber, String merchantOrderRef) {
        if (!isNew) {
            throw new IllegalStateException("The parcel of a stored delivery can't change");
        }
        this.parcelId = parcelId;
        this.trackingNumber = trackingNumber;
        this.merchantOrderRef = merchantOrderRef;
        return this;
    }

    public WebhookEventType eventType() {
        return WebhookEventType.fromWireName(eventType)
                .orElseThrow(() -> new IllegalStateException("Unknown webhook event type in database: " + eventType));
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
