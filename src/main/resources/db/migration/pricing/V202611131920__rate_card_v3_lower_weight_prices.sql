-- pricing: rate card version 3, lower weight prices and no cash on delivery handling fee.
-- Base fee 250.00 for the first kg (was 350.00), 75.00 for every billable kg above it (was 200.00), and no COD
-- handling fee (was 100.00): with a COD fee of 0 the quote has no COD line. The vehicle leg prices, the linehaul rate,
-- the road factor and the volumetric divisor are the same as in version 2.
-- Version 2 stays for history (a parcel keeps the rate card version it was quoted with) but is no longer active.

-- First, so that the new version can be the one active rate card (uk_rate_cards_one_active).
UPDATE pricing.rate_cards SET active = false WHERE version = 2;

INSERT INTO pricing.rate_cards (version, currency, base_fee, base_weight_kg, per_kg_fee, volumetric_divisor, cod_fee,
                                road_factor, linehaul_per_km, active)
VALUES (3, 'LKR', 250.00, 1, 75.00, 5000, 0.00, 1.30, 2.00, true);

INSERT INTO pricing.vehicle_rates (rate_card_id, vehicle, check_order, max_length_cm, max_width_cm, max_height_cm,
                                   max_weight_kg, surcharge, included_km, per_km_fee)
SELECT id, 'BIKE', 1, 45, 35, 35, 10.00, 150.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 3
UNION ALL
SELECT id, 'TUK_TUK', 2, 80, 60, 60, 40.00, 300.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 3
UNION ALL
SELECT id, 'LORRY', 3, 250, 150, 150, 500.00, 1500.00, 0.0, 0.00 FROM pricing.rate_cards WHERE version = 3;
