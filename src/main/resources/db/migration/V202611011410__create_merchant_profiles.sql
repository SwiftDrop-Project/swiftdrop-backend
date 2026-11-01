CREATE TABLE merchant_profiles (
    user_id           UUID         PRIMARY KEY,
    business_name     VARCHAR(120) NOT NULL,
    business_reg_no   VARCHAR(30),
    parcels_per_month VARCHAR(20)  NOT NULL,
    pickup_district   VARCHAR(30)  NOT NULL,
    city              VARCHAR(80)  NOT NULL,
    approval_status   VARCHAR(20)  NOT NULL,
    reviewed_at       TIMESTAMPTZ,
    reviewed_by       UUID,
    CONSTRAINT fk_merchant_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_merchant_profiles_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_merchant_profiles_parcels CHECK (parcels_per_month IN ('UNDER_50', 'FROM_50_TO_200', 'FROM_200_TO_1000', 'OVER_1000')),
    CONSTRAINT ck_merchant_profiles_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_merchant_profiles_approval_status ON merchant_profiles (approval_status);
