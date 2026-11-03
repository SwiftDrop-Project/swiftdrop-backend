-- Email verification, password reset/change and server-side session ends.

-- New status PENDING_EMAIL_VERIFICATION (26 characters) needs a wider column.
ALTER TABLE identity.users ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE identity.users DROP CONSTRAINT ck_users_status;
ALTER TABLE identity.users ADD CONSTRAINT ck_users_status CHECK (status IN
    ('ACTIVE', 'PENDING_EMAIL_VERIFICATION', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED', 'PROFILE_INCOMPLETE'));

-- Copied into every JWT as "ver". Incrementing it (password change/reset) ends all existing sessions.
ALTER TABLE identity.users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- One-time 6-digit codes sent by email. Only an HMAC-SHA256 of the code is stored, never the code.
CREATE TABLE identity.verification_codes (
    id          UUID        PRIMARY KEY,
    -- identity.users.id; a plain UUID like everywhere else (no foreign keys)
    user_id     UUID        NOT NULL,
    purpose     VARCHAR(30) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    -- wrong guesses; the code is dead after 5
    attempts    SMALLINT    NOT NULL DEFAULT 0,
    -- set when the code was used or replaced by a newer one
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_verification_codes_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

CREATE INDEX ix_verification_codes_user_purpose_created
    ON identity.verification_codes (user_id, purpose, created_at DESC);
