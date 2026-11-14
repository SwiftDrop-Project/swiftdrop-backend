
CREATE TABLE delivery_settings (
    id BIGINT PRIMARY KEY,

    pickup_start TIME NOT NULL,
    pickup_end TIME NOT NULL,
    same_day_cutoff TIME NOT NULL,

    operating_days VARCHAR(255) NOT NULL,

    max_weight_kg NUMERIC(10, 2),
    max_length_cm NUMERIC(10, 2),
    max_width_cm NUMERIC(10, 2),
    max_height_cm NUMERIC(10, 2),

    failed_attempts INTEGER NOT NULL,

    CONSTRAINT chk_delivery_settings_singleton
        CHECK (id = 1),

    CONSTRAINT chk_delivery_failed_attempts
        CHECK (failed_attempts BETWEEN 1 AND 5),

    CONSTRAINT chk_delivery_pickup_hours
        CHECK (pickup_start < pickup_end),

    CONSTRAINT chk_delivery_cutoff
        CHECK (
            same_day_cutoff >= pickup_start
            AND same_day_cutoff <= pickup_end
        ),

    CONSTRAINT chk_delivery_max_weight
        CHECK (max_weight_kg > 0),

    CONSTRAINT chk_delivery_max_length
        CHECK (max_length_cm > 0),

    CONSTRAINT chk_delivery_max_width
        CHECK (max_width_cm > 0),

    CONSTRAINT chk_delivery_max_height
        CHECK (max_height_cm > 0)
);
