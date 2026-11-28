-- payments module baseline: one payment record for every customer delivery (the customer's "Payments" page).
CREATE SCHEMA IF NOT EXISTS payments;

-- parcel_id is delivery.parcels.id and customer_id is identity.users.id, stored as plain UUIDs: no foreign keys or
-- joins across schemas. tracking_number, booked_at, method, amount and currency are copied from the parcel when the
-- record is made, so the page can sort and page in this table alone.
-- method: how the fee is paid (delivery.PaymentMethod), NULL for parcels booked before payment methods (treated as
-- cash on delivery). status: DUE, PAID or NOT_CHARGED. A mock card payment keeps only the card brand and last 4 digits:
-- never the full number or the security code.
CREATE TABLE payments.payments (
    id              UUID           PRIMARY KEY,
    parcel_id       UUID           NOT NULL,
    customer_id     UUID           NOT NULL,
    tracking_number VARCHAR(20)    NOT NULL,
    booked_at       TIMESTAMPTZ    NOT NULL,
    method          VARCHAR(20),
    amount          NUMERIC(12, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    card_brand      VARCHAR(20),
    card_last4      VARCHAR(4),
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_parcel UNIQUE (parcel_id),
    CONSTRAINT ck_payments_method CHECK (method IS NULL OR method IN ('ONLINE', 'AT_PICKUP', 'AT_DROP_OFF')),
    CONSTRAINT ck_payments_status CHECK (status IN ('DUE', 'PAID', 'NOT_CHARGED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0)
);

-- The Payments page: one customer's payments, newest booking first.
CREATE INDEX ix_payments_customer ON payments.payments (customer_id, booked_at DESC, id DESC);
