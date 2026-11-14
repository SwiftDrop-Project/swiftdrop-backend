-- webhooks module (WP6): merchant webhook subscriptions and the deliveries sent to them (API.md "Webhooks").
-- merchant_id is an identity user id and subscription_id is webhooks.subscriptions.id, both plain UUIDs:
-- no foreign keys (as everywhere in this project). Rows are never deleted: a subscription is turned off instead.

CREATE SCHEMA IF NOT EXISTS webhooks;

CREATE TABLE webhooks.subscriptions (
    id                UUID          PRIMARY KEY,
    -- identity user id of the MERCHANT
    merchant_id       UUID          NOT NULL,
    -- https:// URL, checked when saved and again before every send
    url               VARCHAR(2048) NOT NULL,
    -- the HMAC signing secret, AES-GCM encrypted (base64 of IV + ciphertext). Never log it.
    secret_ciphertext VARCHAR(255)  NOT NULL,
    -- wire names, e.g. {parcel.delivered,parcel.failed}; webhook.ping is never stored here
    event_types       TEXT[]        NOT NULL,
    -- false = turned off by the merchant
    active            BOOLEAN       NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    -- optimistic locking (@Version)
    version           BIGINT        NOT NULL,
    CONSTRAINT ck_subscriptions_event_types_not_empty CHECK (cardinality(event_types) BETWEEN 1 AND 7),
    CONSTRAINT ck_subscriptions_event_types_known CHECK (event_types <@ ARRAY[
        'parcel.created', 'parcel.picked_up', 'parcel.at_branch', 'parcel.in_transit',
        'parcel.out_for_delivery', 'parcel.delivered', 'parcel.failed']::TEXT[])
);

-- GET /merchant/webhooks: the merchant's subscriptions, newest first
CREATE INDEX idx_subscriptions_merchant ON webhooks.subscriptions (merchant_id, created_at DESC);
-- fan-out (the merchant's active subscriptions) and the 5-active limit
CREATE INDEX idx_subscriptions_merchant_active ON webhooks.subscriptions (merchant_id) WHERE active;

CREATE TABLE webhooks.deliveries (
    id               UUID         PRIMARY KEY,
    -- ParcelStatusChanged.eventId, or a random id for webhook.ping; the payload's "id"
    event_id         UUID         NOT NULL,
    subscription_id  UUID         NOT NULL,
    -- copied from the subscription, for the admin list and ownership checks
    merchant_id      UUID         NOT NULL,
    event_type       VARCHAR(50)  NOT NULL,
    -- the exact JSON body sent on every attempt (TEXT, not JSONB, so the bytes never change)
    payload          TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempts         INTEGER      NOT NULL,
    -- when the worker may send it next; set only while PENDING
    next_attempt_at  TIMESTAMPTZ,
    -- HTTP status of the last attempt (null: no answer, e.g. a timeout)
    last_status_code INTEGER,
    -- short code only (HTTP_500, TIMEOUT, CONNECTION_FAILED, URL_NOT_ALLOWED); never a response body
    last_error       VARCHAR(50),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    delivered_at     TIMESTAMPTZ,
    -- one delivery per event and subscription: a redelivered RabbitMQ message adds nothing
    CONSTRAINT uk_deliveries_event_subscription UNIQUE (event_id, subscription_id),
    CONSTRAINT ck_deliveries_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_deliveries_event_type CHECK (event_type IN (
        'parcel.created', 'parcel.picked_up', 'parcel.at_branch', 'parcel.in_transit',
        'parcel.out_for_delivery', 'parcel.delivered', 'parcel.failed', 'webhook.ping')),
    CONSTRAINT ck_deliveries_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_deliveries_next_attempt CHECK ((next_attempt_at IS NOT NULL) = (status = 'PENDING')),
    CONSTRAINT ck_deliveries_delivered CHECK ((delivered_at IS NOT NULL) = (status = 'SUCCEEDED'))
);

-- the worker: PENDING deliveries that are due, oldest first. Finished rows leave the index, so it stays small.
CREATE INDEX idx_deliveries_due ON webhooks.deliveries (next_attempt_at) WHERE status = 'PENDING';
-- GET /merchant/webhooks/{id}/deliveries: one subscription's history, newest first
CREATE INDEX idx_deliveries_subscription ON webhooks.deliveries (subscription_id, created_at DESC);
-- GET /admin/webhooks/deliveries?status=: newest first
CREATE INDEX idx_deliveries_status ON webhooks.deliveries (status, created_at DESC);
