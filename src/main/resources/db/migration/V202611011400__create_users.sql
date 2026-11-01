CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(254) NOT NULL,
    phone         VARCHAR(12)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'COURIER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'REJECTED', 'DISABLED'))
);
