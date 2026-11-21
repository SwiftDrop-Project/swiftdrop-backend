-- delivery module: how the delivery fee is paid, chosen when the parcel is booked (customer bookings and merchant
-- orders). The courier uses it to know whether to collect the fee, and from whom. It is not the cash on delivery
-- (cod_amount), which is the price of the goods.
-- Additive only: a new nullable column. Parcels booked before it have none; the application asks for it on new ones.

ALTER TABLE delivery.parcels ADD COLUMN payment_method VARCHAR(20);

ALTER TABLE delivery.parcels ADD CONSTRAINT ck_parcels_payment_method
    CHECK (payment_method IN ('ONLINE', 'AT_PICKUP', 'AT_DROP_OFF'));
