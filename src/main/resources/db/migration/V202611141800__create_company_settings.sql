
CREATE TABLE IF NOT EXISTS company_settings (
    id BIGINT PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    support_email VARCHAR(255) NOT NULL,
    support_phone VARCHAR(30) NOT NULL,
    head_office_address TEXT NOT NULL,
    registration_number VARCHAR(255),

    CONSTRAINT chk_company_settings_singleton
        CHECK (id = 1)
);

