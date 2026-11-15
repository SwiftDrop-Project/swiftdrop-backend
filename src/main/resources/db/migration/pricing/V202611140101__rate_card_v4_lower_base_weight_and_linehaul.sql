-- pricing: rate card version 4, lower base fee, weight charge and linehaul.
-- Base fee 150.00 for the first kg (was 250.00), 50.00 for every billable kg above it (was 75.00), and linehaul 1.00
-- per km between the pickup and drop-off district branches (was 2.00). Still no COD handling fee; the vehicle leg
-- prices, the road factor and the volumetric divisor are the same as in version 3.
-- Version 3 stays for history (a parcel keeps the rate card version it was quoted with) but is no longer active.

-- First, so that the new version can be the one active rate card (uk_rate_cards_one_active).
UPDATE pricing.rate_cards SET active = false WHERE version = 3;

INSERT INTO pricing.rate_cards (version, currency, base_fee, base_weight_kg, per_kg_fee, volumetric_divisor, cod_fee,
                                road_factor, linehaul_per_km, active)
VALUES (4, 'LKR', 150.00, 1, 50.00, 5000, 0.00, 1.30, 1.00, true);

INSERT INTO pricing.vehicle_rates (rate_card_id, vehicle, check_order, max_length_cm, max_width_cm, max_height_cm,
                                   max_weight_kg, surcharge, included_km, per_km_fee)
SELECT id, 'BIKE', 1, 45, 35, 35, 10.00, 150.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 4
UNION ALL
SELECT id, 'TUK_TUK', 2, 80, 60, 60, 40.00, 300.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 4
UNION ALL
SELECT id, 'LORRY', 3, 250, 150, 150, 500.00, 1500.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 4;
