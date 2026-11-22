-- delivery module: the transactional outbox. Delivery writes one row in the same transaction as each status change.
-- The outbox module's relay reads unpublished rows through DeliveryOutboxApi (never this table directly), publishes
-- them to RabbitMQ (exchange parcel.events) and then sets published_at.
-- parcel_id is delivery.parcels.id, a plain UUID (no foreign keys).

CREATE TABLE delivery.outbox_events (
    -- ParcelStatusChanged.eventId: consumers drop duplicates by it
    event_id     UUID        PRIMARY KEY,
    parcel_id    UUID        NOT NULL,
    -- the RabbitMQ routing key, e.g. parcel.status.changed
    event_type   VARCHAR(50) NOT NULL,
    -- the event as JSON; it may hold personal data (the note): never log it
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    -- null until RabbitMQ has confirmed the message (DeliveryOutboxApi.markPublished)
    published_at TIMESTAMPTZ
);

-- fetchUnpublished(): unpublished events, oldest first. Published rows leave the index, so it stays small.
CREATE INDEX idx_outbox_events_unpublished ON delivery.outbox_events (created_at)
    WHERE published_at IS NULL;
