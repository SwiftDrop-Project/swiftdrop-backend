-- Search in the delivery history (API.md "Webhooks"): merchants look for their parcels by tracking number or their own
-- order number, so those values get real columns instead of living only inside the JSON payload.
-- Additive only: three nullable columns, no foreign keys. They stay NULL for test pings (which have no parcel).

ALTER TABLE webhooks.deliveries ADD COLUMN parcel_id          UUID;
ALTER TABLE webhooks.deliveries ADD COLUMN tracking_number    VARCHAR(20);
ALTER TABLE webhooks.deliveries ADD COLUMN merchant_order_ref VARCHAR(64);

-- Fill rows that already exist from their payload ({"data": {"parcelId", "trackingNumber", "merchantOrderRef"}}).
-- Only parcel events, only payloads that are JSON objects, and only a well-formed parcel id, so an odd old row can't
-- make this migration fail; anything that doesn't fit stays NULL.
UPDATE webhooks.deliveries d
SET parcel_id          = CASE
                             WHEN p.data ->> 'parcelId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                 THEN (p.data ->> 'parcelId')::uuid
                         END,
    tracking_number    = left(p.data ->> 'trackingNumber', 20),
    merchant_order_ref = left(p.data ->> 'merchantOrderRef', 64)
FROM (SELECT id, payload::jsonb -> 'data' AS data
      FROM webhooks.deliveries
      WHERE event_type <> 'webhook.ping'
        AND payload LIKE '{%'
        AND jsonb_typeof(payload::jsonb -> 'data') = 'object') p
WHERE d.id = p.id;

-- The searches: exact tracking number and exact parcel id. (The order number is matched by "contains", which a
-- normal index can't speed up; the merchant's own subscription filter keeps that search small.)
CREATE INDEX idx_deliveries_tracking_number ON webhooks.deliveries (tracking_number);
CREATE INDEX idx_deliveries_parcel ON webhooks.deliveries (parcel_id);
