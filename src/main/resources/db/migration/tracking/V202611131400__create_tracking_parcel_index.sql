CREATE SCHEMA IF NOT EXISTS tracking;

-- Every parcel's latest status, for the admin's all-parcels tracking page. Delivery's API has no "list all parcels"
-- query, so tracking keeps this index itself: a row is written from delivery's snapshot whenever a ParcelStatusChanged
-- event arrives (RabbitMQ), and whenever someone looks the parcel up by tracking number. Parcels created before this
-- table existed appear after their next change or lookup.
-- No personal data: only the town and district at each end (the same as the public tracking page shows).
CREATE TABLE tracking.parcel_index (
    parcel_id            UUID          NOT NULL,
    tracking_number      VARCHAR(20)   NOT NULL,
    status               VARCHAR(30)   NOT NULL,
    origin_city          VARCHAR(100),
    origin_district      VARCHAR(30),
    destination_city     VARCHAR(100),
    destination_district VARCHAR(30),
    -- the branch the parcel is at (only while AT_BRANCH)
    current_branch       VARCHAR(30),
    -- only while FAILED: delivery's fixed reason code
    failure_reason       VARCHAR(40),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    -- the parcel's version when the row was written, so an older snapshot can't overwrite a newer one
    parcel_version       BIGINT        NOT NULL,
    CONSTRAINT pk_parcel_index PRIMARY KEY (parcel_id),
    CONSTRAINT uq_parcel_index_tracking_number UNIQUE (tracking_number)
);

CREATE INDEX idx_parcel_index_updated ON tracking.parcel_index (updated_at DESC);
CREATE INDEX idx_parcel_index_status ON tracking.parcel_index (status, updated_at DESC);
CREATE INDEX idx_parcel_index_origin ON tracking.parcel_index (origin_district);
CREATE INDEX idx_parcel_index_destination ON tracking.parcel_index (destination_district);
CREATE INDEX idx_parcel_index_branch ON tracking.parcel_index (current_branch);
