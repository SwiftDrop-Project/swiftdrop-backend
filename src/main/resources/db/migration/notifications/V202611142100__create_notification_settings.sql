
CREATE TABLE notification_settings (
    id BIGINT PRIMARY KEY,

    parcel_status_emails BOOLEAN NOT NULL,

    merchant_decision_emails BOOLEAN NOT NULL,

    sender_name VARCHAR(100) NOT NULL,

    CONSTRAINT chk_notification_settings_singleton
        CHECK (id = 1),

    CONSTRAINT chk_notification_sender_name
        CHECK (LENGTH(TRIM(sender_name)) > 0)
);
