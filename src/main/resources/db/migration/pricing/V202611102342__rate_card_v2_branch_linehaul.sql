-- pricing: rate card version 2, for SwiftDrop's branch network.
-- A parcel travels pickup address -> pickup district branch -> drop-off district branch -> drop-off address.
-- Only the two local legs use a Bike, Tuk-tuk or Lorry (the smallest that takes the parcel), each at that vehicle's
-- fixed leg price with no distance charge (addresses have no coordinates yet). Between the branches SwiftDrop's own
-- transport carries it, whatever its size, at linehaul_per_km for the distance between the district centres.
-- Fee = base fee (first kg) + weight charge + pickup leg + delivery leg + linehaul + COD fee.
--
-- vehicle_rates.surcharge is now the price of one local leg. included_km and per_km_fee are not used (0 in this
-- version) until addresses have coordinates. Version 1 stays for history but is no longer active.

ALTER TABLE pricing.rate_cards ADD COLUMN linehaul_per_km NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE pricing.rate_cards ADD CONSTRAINT ck_rate_cards_linehaul CHECK (linehaul_per_km >= 0);

-- First, so that the new version can be the one active rate card (uk_rate_cards_one_active).
UPDATE pricing.rate_cards SET active = false WHERE version = 1;

INSERT INTO pricing.rate_cards (version, currency, base_fee, base_weight_kg, per_kg_fee, volumetric_divisor, cod_fee,
                                road_factor, linehaul_per_km, active)
VALUES (2, 'LKR', 350.00, 1, 200.00, 5000, 100.00, 1.30, 2.00, true);

INSERT INTO pricing.vehicle_rates (rate_card_id, vehicle, check_order, max_length_cm, max_width_cm, max_height_cm,
                                   max_weight_kg, surcharge, included_km, per_km_fee)
SELECT id, 'BIKE', 1, 45, 35, 35, 10.00, 150.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 2
UNION ALL
SELECT id, 'TUK_TUK', 2, 80, 60, 60, 40.00, 300.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 2
UNION ALL
SELECT id, 'LORRY', 3, 250, 150, 150, 500.00, 1500.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 2;
