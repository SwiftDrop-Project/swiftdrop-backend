-- delivery module: the payment of a parcel's delivery fee (WP9, courier fee collection).
-- One successful payment per parcel (uk_fee_payments_parcel_id), never two, even if two requests race. No row means the
-- fee is not paid yet; the parcel's payment_method says how it will be paid.
-- source: COURIER_CASH = a courier collected the fee in cash (AT_PICKUP from the sender at PICKED_UP, AT_DROP_OFF from
-- the recipient at DELIVERED); ONLINE = a verified online payment, kept for the payments work (nothing writes it yet:
-- choosing ONLINE at booking never creates a row).
-- amount and currency are copied from delivery.parcels when the payment is recorded; they never come from a client.
-- parcel_id is delivery.parcels.id, collected_by an identity user id, assignment_id a dispatch task id: plain UUIDs, no
-- foreign keys (project rule). Additive only: a new table, nothing else changes.

CREATE TABLE delivery.fee_payments (
    id                 UUID           PRIMARY KEY,
    parcel_id          UUID           NOT NULL,
    amount             NUMERIC(10, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    payment_method     VARCHAR(20)    NOT NULL,
    source             VARCHAR(20)    NOT NULL,
    -- COURIER_CASH: the courier who collected it and the dispatch task it was collected in; null for ONLINE
    collected_by       UUID,
    assignment_id      UUID,
    -- ONLINE: the payment provider's reference of the verified payment; null for cash
    external_reference VARCHAR(100),
    collected_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_fee_payments_parcel_id UNIQUE (parcel_id),
    CONSTRAINT ck_fee_payments_amount CHECK (amount >= 0),
    CONSTRAINT ck_fee_payments_payment_method CHECK (payment_method IN ('ONLINE', 'AT_PICKUP', 'AT_DROP_OFF')),
    CONSTRAINT ck_fee_payments_source CHECK (source IN ('COURIER_CASH', 'ONLINE')),
    CONSTRAINT ck_fee_payments_courier_cash CHECK (source <> 'COURIER_CASH'
        OR (payment_method IN ('AT_PICKUP', 'AT_DROP_OFF') AND collected_by IS NOT NULL AND assignment_id IS NOT NULL
            AND external_reference IS NULL)),
    CONSTRAINT ck_fee_payments_online CHECK (source <> 'ONLINE'
        OR (payment_method = 'ONLINE' AND collected_by IS NULL AND assignment_id IS NULL
            AND external_reference IS NOT NULL))
);
