-- delivery module: parcels, from booking to hand-over.
-- customer_id, merchant_id and assigned_courier_id are identity user ids, stored as plain UUIDs:
-- no foreign keys or joins across schemas (and, as everywhere in this project, no foreign keys at all).

CREATE SCHEMA IF NOT EXISTS delivery;

CREATE TABLE delivery.parcels (
    id                     UUID           PRIMARY KEY,
    -- normalised, e.g. SD2611004817
    tracking_number        VARCHAR(20)    NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    -- exactly one of customer_id and merchant_id is set
    customer_id            UUID,
    merchant_id            UUID,
    -- the merchant's own order id; a retry with the same one returns the first parcel
    merchant_order_ref     VARCHAR(64),
    pickup_name            VARCHAR(100)   NOT NULL,
    -- +947XXXXXXXX
    pickup_phone           VARCHAR(12)    NOT NULL,
    pickup_address_line    VARCHAR(200)   NOT NULL,
    pickup_city            VARCHAR(80)    NOT NULL,
    pickup_district        VARCHAR(30)    NOT NULL,
    recipient_name         VARCHAR(100)   NOT NULL,
    recipient_phone        VARCHAR(12)    NOT NULL,
    recipient_address_line VARCHAR(200)   NOT NULL,
    recipient_city         VARCHAR(80)    NOT NULL,
    recipient_district     VARCHAR(30)    NOT NULL,
    weight_kg              NUMERIC(6, 2)  NOT NULL,
    -- cash to collect on delivery, in rupees; null when already paid
    cod_amount             NUMERIC(10, 2),
    instructions           VARCHAR(500),
    -- the fee from pricing, fixed when the parcel was booked
    fee_amount             NUMERIC(10, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    assigned_courier_id    UUID,
    assigned_at            TIMESTAMPTZ,
    -- proof of delivery, only for DELIVERED
    proof_recipient_name   VARCHAR(100),
    proof_photo_id         UUID,
    -- only for FAILED
    failure_reason         VARCHAR(30),
    created_at             TIMESTAMPTZ    NOT NULL,
    -- the last change of any kind (status, assignment, proof)
    updated_at             TIMESTAMPTZ    NOT NULL,
    -- the last status change; the courier lists and the reports use it
    status_changed_at      TIMESTAMPTZ    NOT NULL,
    -- optimistic locking (@Version); sent as ParcelStatusChanged.parcelVersion
    version                BIGINT         NOT NULL,
    CONSTRAINT uk_parcels_tracking_number UNIQUE (tracking_number),
    CONSTRAINT uk_parcels_merchant_order_ref UNIQUE (merchant_id, merchant_order_ref),
    CONSTRAINT ck_parcels_status CHECK (status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_parcels_one_owner CHECK ((customer_id IS NULL) <> (merchant_id IS NULL)),
    CONSTRAINT ck_parcels_weight CHECK (weight_kg > 0),
    CONSTRAINT ck_parcels_cod_amount CHECK (cod_amount > 0),
    CONSTRAINT ck_parcels_fee_amount CHECK (fee_amount >= 0),
    CONSTRAINT ck_parcels_assignment CHECK ((assigned_courier_id IS NULL) = (assigned_at IS NULL)),
    CONSTRAINT ck_parcels_failure_reason CHECK (failure_reason IN ('RECIPIENT_UNAVAILABLE', 'WRONG_ADDRESS', 'RECIPIENT_REFUSED', 'ADDRESS_UNREACHABLE', 'PARCEL_DAMAGED', 'OTHER')),
    CONSTRAINT ck_parcels_failure_only_when_failed CHECK (failure_reason IS NULL OR status = 'FAILED'),
    CONSTRAINT ck_parcels_proof_only_when_delivered CHECK (status = 'DELIVERED' OR (proof_recipient_name IS NULL AND proof_photo_id IS NULL))
);

CREATE INDEX idx_parcels_customer_id         ON delivery.parcels (customer_id, created_at DESC);
CREATE INDEX idx_parcels_merchant_id         ON delivery.parcels (merchant_id, created_at DESC);
CREATE INDEX idx_parcels_assigned_courier_id ON delivery.parcels (assigned_courier_id, status_changed_at DESC);
-- parcels waiting for a courier, oldest first (findUnassigned)
CREATE INDEX idx_parcels_unassigned          ON delivery.parcels (created_at)
    WHERE status = 'CREATED' AND assigned_courier_id IS NULL;
CREATE INDEX idx_parcels_created_at          ON delivery.parcels (created_at);
