-- "Continue with Google": Google-only accounts have no password, and no phone until they complete their profile.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

-- Google's stable account id (the OIDC "sub" claim). NULL for accounts that never used Google.
-- PostgreSQL allows many NULLs in a UNIQUE column.
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT uk_users_google_sub UNIQUE (google_sub);

ALTER TABLE users DROP CONSTRAINT ck_users_status;
ALTER TABLE users ADD CONSTRAINT ck_users_status
    CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED', 'PROFILE_INCOMPLETE'));
