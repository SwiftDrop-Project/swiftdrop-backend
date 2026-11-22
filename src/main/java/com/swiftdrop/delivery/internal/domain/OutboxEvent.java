package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.OutboxMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One event in delivery's outbox (delivery.outbox_events). Delivery writes it in the same transaction as the parcel
 * change; the outbox relay reads it through DeliveryOutboxApi, publishes it and marks it published.
 * The payload may hold personal data (the note): no toString on purpose.
 */
@Entity
@Table(name = "outbox_events", schema = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent implements Persistable<UUID> {

    /** Event type of ParcelStatusChanged, also its RabbitMQ routing key on the parcel.events exchange. */
    public static final String PARCEL_STATUS_CHANGED = "parcel.status.changed";

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** The event as JSON text. Hibernate stores it in the jsonb column as it is. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Transient
    private boolean isNew = true;

    /** A new, unpublished event. */
    public static OutboxEvent of(UUID eventId, UUID parcelId, String eventType, String payload, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.eventId = eventId;
        event.parcelId = parcelId;
        event.eventType = eventType;
        event.payload = payload;
        event.createdAt = now;
        return event;
    }

    /** Called after RabbitMQ has confirmed the message. Calling it again keeps the first time. */
    public void markPublished(Instant now) {
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    /** The public view for the relay (DeliveryOutboxApi.fetchUnpublished). */
    public OutboxMessage toMessage() {
        return new OutboxMessage(eventId, parcelId, eventType, payload, createdAt);
    }

    @Override
    public UUID getId() {
        return eventId;
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
