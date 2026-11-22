-- delivery module: a parcel's packed size and how its fee was worked out (pricing, WP4).
-- A customer booking now gives the outside measurements in whole cm and whether the parcel must stay upright;
-- pricing uses them for the billable weight and the vehicle of the local legs. The parcel keeps the vehicle, the
-- billable weight, the rate card version and the fee lines, so the quoted price can be reproduced later.
-- Additive only: new nullable columns (parcels booked before sizes have none) and keep_upright with a default.

ALTER TABLE delivery.parcels
    ADD COLUMN length_cm          INTEGER,
    ADD COLUMN width_cm           INTEGER,
    ADD COLUMN height_cm          INTEGER,
    ADD COLUMN keep_upright       BOOLEAN NOT NULL DEFAULT false,
    -- a pricing.Vehicle name
    ADD COLUMN vehicle            VARCHAR(20),
    ADD COLUMN billable_weight_kg INTEGER,
    -- pricing.rate_cards.version, a plain number (no foreign keys)
    ADD COLUMN rate_card_version  INTEGER,
    -- the fee lines, e.g. [{"component":"BASE","amount":350.00}, ...]
    ADD COLUMN fee_breakdown      JSONB;

-- All three measurements or none.
ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_size CHECK (
    (length_cm IS NULL AND width_cm IS NULL AND height_cm IS NULL)
    OR (length_cm > 0 AND width_cm > 0 AND height_cm > 0));
ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_vehicle CHECK (vehicle IN ('BIKE', 'TUK_TUK', 'LORRY'));
ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_billable_weight CHECK (billable_weight_kg > 0);
