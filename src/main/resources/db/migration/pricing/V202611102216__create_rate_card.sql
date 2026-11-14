-- pricing module: the rate card the delivery fee estimate is worked out from.
-- Estimated fee = base fee (includes the first base_weight_kg) + per_kg_fee for every billable kg above it
--               + the vehicle's surcharge + max(0, distance - included_km) x the vehicle's per_km_fee + cod_fee.
-- Billable weight is the greater of the actual and the volumetric weight (L x W x H / volumetric_divisor), rounded up.
-- The vehicle is the first, by check_order, that carries the weight and fits the parcel.
-- Prices change by adding a new rate card version and making it the active one, never by editing a used one, so a
-- booking can keep the version it was quoted with. No foreign keys: rate_card_id is a plain UUID.

CREATE SCHEMA IF NOT EXISTS pricing;

CREATE TABLE pricing.rate_cards (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    version            INTEGER       NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    -- covers the first base_weight_kg billable kilograms
    base_fee           NUMERIC(10,2) NOT NULL,
    base_weight_kg     INTEGER       NOT NULL,
    per_kg_fee         NUMERIC(10,2) NOT NULL,
    -- volumetric weight (kg) = length x width x height (cm) / volumetric_divisor
    volumetric_divisor INTEGER       NOT NULL,
    -- handling fee when the courier collects cash on delivery
    cod_fee            NUMERIC(10,2) NOT NULL,
    -- straight-line distance between district centres x road_factor = estimated road distance
    road_factor        NUMERIC(4,2)  NOT NULL,
    active             BOOLEAN       NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_rate_cards_version UNIQUE (version),
    CONSTRAINT ck_rate_cards_version CHECK (version > 0),
    CONSTRAINT ck_rate_cards_fees CHECK (base_fee >= 0 AND per_kg_fee >= 0 AND cod_fee >= 0),
    CONSTRAINT ck_rate_cards_base_weight CHECK (base_weight_kg >= 0),
    CONSTRAINT ck_rate_cards_divisor CHECK (volumetric_divisor > 0),
    CONSTRAINT ck_rate_cards_road_factor CHECK (road_factor >= 1)
);

-- At most one active rate card: the index only holds the rows where active is true.
CREATE UNIQUE INDEX uk_rate_cards_one_active ON pricing.rate_cards (active) WHERE active;

CREATE TABLE pricing.vehicle_rates (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    -- pricing.rate_cards.id
    rate_card_id  UUID          NOT NULL,
    vehicle       VARCHAR(20)   NOT NULL,
    -- the smallest vehicle is checked first: 1 Bike, 2 Tuk-tuk, 3 Lorry
    check_order   INTEGER       NOT NULL,
    -- usable cargo space; the parcel must fit in some allowed orientation
    max_length_cm INTEGER       NOT NULL,
    max_width_cm  INTEGER       NOT NULL,
    max_height_cm INTEGER       NOT NULL,
    -- the actual (not billable) weight it may carry
    max_weight_kg NUMERIC(7,2)  NOT NULL,
    surcharge     NUMERIC(10,2) NOT NULL,
    included_km   NUMERIC(6,1)  NOT NULL,
    per_km_fee    NUMERIC(10,2) NOT NULL,
    CONSTRAINT uk_vehicle_rates_vehicle UNIQUE (rate_card_id, vehicle),
    CONSTRAINT uk_vehicle_rates_check_order UNIQUE (rate_card_id, check_order),
    CONSTRAINT ck_vehicle_rates_vehicle CHECK (vehicle IN ('BIKE', 'TUK_TUK', 'LORRY')),
    CONSTRAINT ck_vehicle_rates_check_order CHECK (check_order > 0),
    CONSTRAINT ck_vehicle_rates_size CHECK (max_length_cm > 0 AND max_width_cm > 0 AND max_height_cm > 0),
    CONSTRAINT ck_vehicle_rates_weight CHECK (max_weight_kg > 0),
    CONSTRAINT ck_vehicle_rates_fees CHECK (surcharge >= 0 AND included_km >= 0 AND per_km_fee >= 0)
);

-- The centre (district capital) of each district, for the distance between a pickup and a drop-off district.
-- district is a shared.reference.District name. A trip inside one district counts as 0 km.
CREATE TABLE pricing.district_locations (
    district  VARCHAR(30)  PRIMARY KEY,
    latitude  NUMERIC(8,5) NOT NULL,
    longitude NUMERIC(8,5) NOT NULL,
    CONSTRAINT ck_district_locations_in_sri_lanka
        CHECK (latitude BETWEEN 5 AND 10 AND longitude BETWEEN 79 AND 82)
);

-- Rate card 1 (demo values). The Bike's figures are the spec's sample; Tuk-tuk and Lorry are set here.
INSERT INTO pricing.rate_cards (version, currency, base_fee, base_weight_kg, per_kg_fee, volumetric_divisor, cod_fee,
                                road_factor, active)
VALUES (1, 'LKR', 350.00, 1, 200.00, 5000, 100.00, 1.30, true);

INSERT INTO pricing.vehicle_rates (rate_card_id, vehicle, check_order, max_length_cm, max_width_cm, max_height_cm,
                                   max_weight_kg, surcharge, included_km, per_km_fee)
SELECT id, 'BIKE', 1, 45, 35, 35, 10.00, 0.00, 5.0, 30.00 FROM pricing.rate_cards WHERE version = 1
UNION ALL
SELECT id, 'TUK_TUK', 2, 80, 60, 60, 40.00, 300.00, 5.0, 60.00 FROM pricing.rate_cards WHERE version = 1
UNION ALL
SELECT id, 'LORRY', 3, 250, 150, 150, 500.00, 1500.00, 10.0, 150.00 FROM pricing.rate_cards WHERE version = 1;

INSERT INTO pricing.district_locations (district, latitude, longitude) VALUES
    ('AMPARA', 7.29750, 81.68200), ('ANURADHAPURA', 8.31140, 80.40370), ('BADULLA', 6.99340, 81.05500),
    ('BATTICALOA', 7.73100, 81.67470), ('COLOMBO', 6.92710, 79.86120), ('GALLE', 6.05350, 80.22100),
    ('GAMPAHA', 7.08730, 79.99900), ('HAMBANTOTA', 6.12410, 81.11850), ('JAFFNA', 9.66150, 80.02550),
    ('KALUTARA', 6.58540, 79.96070), ('KANDY', 7.29060, 80.63370), ('KEGALLE', 7.25130, 80.34640),
    ('KILINOCHCHI', 9.38030, 80.37700), ('KURUNEGALA', 7.48630, 80.36470), ('MANNAR', 8.98100, 79.90440),
    ('MATALE', 7.46750, 80.62340), ('MATARA', 5.95490, 80.55500), ('MONARAGALA', 6.87280, 81.35070),
    ('MULLAITIVU', 9.26710, 80.81420), ('NUWARA_ELIYA', 6.94970, 80.78910), ('POLONNARUWA', 7.94030, 81.01880),
    ('PUTTALAM', 8.03620, 79.82830), ('RATNAPURA', 6.68280, 80.39920), ('TRINCOMALEE', 8.58740, 81.21520),
    ('VAVUNIYA', 8.75140, 80.49710);
