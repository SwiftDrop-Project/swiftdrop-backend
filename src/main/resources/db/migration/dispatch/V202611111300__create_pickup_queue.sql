-- Parcels waiting to be picked up (status CREATED), by the sender's district, so the dispatcher can plan pickup tasks.
-- Delivery's API has no "waiting for pickup" query, so dispatch keeps this list itself from the ParcelStatusChanged
-- events on RabbitMQ (queue dispatch.q): a row is added when a parcel is created and removed when it leaves CREATED.
-- No personal data: only ids, the tracking number and the district. The rest is read from delivery when shown.
CREATE TABLE dispatch.pickup_queue (
    parcel_id       UUID         NOT NULL,
    tracking_number VARCHAR(20)  NOT NULL,
    pickup_district VARCHAR(30)  NOT NULL,
    -- the parcel's version when the row was written, so an older event can't overwrite a newer one
    parcel_version  BIGINT       NOT NULL,
    -- when the parcel was created (oldest first in the queue)
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_pickup_queue PRIMARY KEY (parcel_id)
);

CREATE INDEX idx_pickup_queue_district ON dispatch.pickup_queue (pickup_district, created_at);
