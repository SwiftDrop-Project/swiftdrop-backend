-- identity module baseline: user accounts.
-- Replaces the old V202611011400 / V202611011410 / V202611011500 migrations (fresh database for everyone).
-- Other modules refer to users by id (UUID) only: no foreign keys or joins into this schema.

CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
    id            UUID         PRIMARY KEY,
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(254) NOT NULL,
    -- +947XXXXXXXX; NULL only while status is PROFILE_INCOMPLETE (Google sign-up not finished)
    phone         VARCHAR(12),
    -- BCrypt; NULL for accounts created with Google (they cannot log in with a password)
    password_hash VARCHAR(100),
    -- Google's stable account id (OIDC "sub"); NULL for accounts that never used Google
    google_sub    VARCHAR(255),
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    -- GlobalExceptionHandler maps uk_users_email / uk_users_phone to 409 errors; keep these names.
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT uk_users_google_sub UNIQUE (google_sub),
    CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'COURIER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED', 'PROFILE_INCOMPLETE'))
);
