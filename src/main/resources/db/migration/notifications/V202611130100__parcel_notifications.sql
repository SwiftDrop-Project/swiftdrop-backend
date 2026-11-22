
-- WP7: Parcel notification history, event deduplication,
-- and reliable email delivery tracking.
-- Apply only after team review.

CREATE TABLE notifications.parcel_notification (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    parcel_id UUID NOT NULL,
    parcel_version BIGINT NOT NULL,
    recipient_id UUID NOT NULL,
    tracking_number VARCHAR(100),
    parcel_status VARCHAR(40) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    email_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    email_attempts INTEGER NOT NULL DEFAULT 0,
    email_last_error VARCHAR(255),
    email_sent_at TIMESTAMPTZ,
    email_next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_parcel_notification_version
        CHECK (parcel_version >= 0),

    CONSTRAINT ck_parcel_notification_email_status
        CHECK (email_status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),

    CONSTRAINT ck_parcel_notification_email_attempts
        CHECK (email_attempts >= 0)
);

CREATE INDEX ix_parcel_notification_recipient
    ON notifications.parcel_notification (recipient_id, created_at DESC);

CREATE INDEX ix_parcel_notification_email_pending
    ON notifications.parcel_notification
        (email_next_attempt_at, created_at)
    WHERE email_status IN ('PENDING', 'FAILED');

CREATE TABLE notifications.parcel_event_progress (
    parcel_id UUID PRIMARY KEY,
    last_processed_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
