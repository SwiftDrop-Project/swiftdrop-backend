-- merchants module baseline: merchant business profiles and their approval.
-- user_id and reviewed_by are identity user ids (identity.users.id), stored as plain UUIDs:
-- no foreign keys or joins across schemas.

CREATE SCHEMA IF NOT EXISTS merchants;

CREATE TABLE merchants.merchant_profiles (
    user_id           UUID         PRIMARY KEY,
    business_name     VARCHAR(120) NOT NULL,
    business_reg_no   VARCHAR(30),
    parcels_per_month VARCHAR(20)  NOT NULL,
    pickup_district   VARCHAR(30)  NOT NULL,
    city              VARCHAR(80)  NOT NULL,
    approval_status   VARCHAR(20)  NOT NULL,
    reviewed_at       TIMESTAMPTZ,
    -- the ADMIN who approved or rejected the application
    reviewed_by       UUID,
    CONSTRAINT ck_merchant_profiles_parcels CHECK (parcels_per_month IN ('UNDER_50', 'FROM_50_TO_200', 'FROM_200_TO_1000', 'OVER_1000')),
    CONSTRAINT ck_merchant_profiles_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_merchant_profiles_approval_status ON merchants.merchant_profiles (approval_status);
