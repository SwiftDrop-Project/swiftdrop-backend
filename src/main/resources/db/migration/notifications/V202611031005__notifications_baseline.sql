-- notifications module baseline: a record of every account email (never the content or the full address).

CREATE SCHEMA IF NOT EXISTS notifications;

CREATE TABLE notifications.email_log (
    id               UUID         PRIMARY KEY,
    -- AccountEmail.Type, e.g. EMAIL_VERIFICATION
    type             VARCHAR(40)  NOT NULL,
    -- e.g. a•••@gmail.com
    recipient_masked VARCHAR(254) NOT NULL,
    status           VARCHAR(10)  NOT NULL,
    -- exception class name when FAILED
    error_class      VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_email_log_status CHECK (status IN ('SENT', 'FAILED'))
);

CREATE INDEX ix_email_log_created_at ON notifications.email_log (created_at DESC);
