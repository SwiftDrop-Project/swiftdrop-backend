-- delivery module: one row for every status change of a parcel, the first one (CREATED) included.
-- parcel_id is delivery.parcels.id and actor_id is an identity user id, both plain UUIDs (no foreign keys).
-- DeliveryApi.history() reads it newest first. Assigning a courier is not a status change, so it adds no row.

CREATE TABLE delivery.parcel_status_history (
    id             UUID         PRIMARY KEY,
    parcel_id      UUID         NOT NULL,
    -- null only for the first row (CREATED)
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    -- who made the change: an identity user id and a role name, e.g. COURIER
    actor_id       UUID         NOT NULL,
    actor_role     VARCHAR(20)  NOT NULL,
    -- free text that may hold personal data: never log it
    note           VARCHAR(500),
    -- only for FAILED
    failure_reason VARCHAR(30),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_parcel_status_history_to_status CHECK (to_status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_parcel_status_history_from_status CHECK (from_status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_parcel_status_history_first_entry CHECK ((from_status IS NULL) = (to_status = 'CREATED')),
    CONSTRAINT ck_parcel_status_history_failure_reason CHECK (failure_reason IN ('RECIPIENT_UNAVAILABLE', 'WRONG_ADDRESS', 'RECIPIENT_REFUSED', 'ADDRESS_UNREACHABLE', 'PARCEL_DAMAGED', 'OTHER')),
    CONSTRAINT ck_parcel_status_history_failure_only_when_failed CHECK (failure_reason IS NULL OR to_status = 'FAILED')
);

-- history(): one parcel's rows, newest first
CREATE INDEX idx_parcel_status_history_parcel_id ON delivery.parcel_status_history (parcel_id, occurred_at DESC);
