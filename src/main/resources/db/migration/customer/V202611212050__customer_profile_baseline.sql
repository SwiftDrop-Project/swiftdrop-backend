-- customer module baseline: customer profile and preferences.
-- user_id is the identity user id (identity.users.id), stored as plain UUID:
-- no foreign keys or joins across schemas.

CREATE SCHEMA IF NOT EXISTS customer;

CREATE TABLE customer.customer_profiles (
    user_id                   UUID         PRIMARY KEY,
    default_pickup_address_id UUID,
    preferred_payment_method  VARCHAR(30)  NOT NULL DEFAULT 'CASH_ON_DELIVERY',
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_customer_profiles_payment CHECK (preferred_payment_method IN ('CASH_ON_DELIVERY', 'MOCK_CARD'))
);
