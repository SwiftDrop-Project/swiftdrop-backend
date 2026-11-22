CREATE TABLE merchants.api_keys (
    id               UUID        PRIMARY KEY,
    merchant_user_id UUID        NOT NULL,
    key_hash         VARCHAR(64) NOT NULL,
    name             VARCHAR(80) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_keys_merchant_user_id ON merchants.api_keys (merchant_user_id);
CREATE INDEX idx_api_keys_key_hash         ON merchants.api_keys (key_hash);
