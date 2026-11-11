-- Internal staff roles (DISPATCHER), courier/dispatcher profile fields, soft-removal (REMOVED),
-- and mandatory first-login / post-reset password change state.

ALTER TABLE identity.users DROP CONSTRAINT ck_users_role;
ALTER TABLE identity.users ADD CONSTRAINT ck_users_role CHECK (role IN
    ('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'ADMIN'));

ALTER TABLE identity.users DROP CONSTRAINT ck_users_status;
ALTER TABLE identity.users ADD CONSTRAINT ck_users_status CHECK (status IN
    ('ACTIVE', 'PENDING_EMAIL_VERIFICATION', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED', 'PROFILE_INCOMPLETE', 'REMOVED'));

ALTER TABLE identity.users
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN branch               VARCHAR(30),
    ADD COLUMN vehicle_type         VARCHAR(30),
    ADD COLUMN deleted_at           TIMESTAMPTZ;

ALTER TABLE identity.users ADD CONSTRAINT ck_users_vehicle_type CHECK (
    vehicle_type IS NULL OR vehicle_type IN ('MOTORBIKE', 'THREE_WHEELER', 'VAN', 'LORRY'));

ALTER TABLE identity.users ADD CONSTRAINT ck_users_deleted_at CHECK (
    (status = 'REMOVED' AND deleted_at IS NOT NULL) OR (status <> 'REMOVED' AND deleted_at IS NULL));

CREATE INDEX ix_users_role_status ON identity.users (role, status);
CREATE INDEX ix_users_branch ON identity.users (branch) WHERE branch IS NOT NULL;

