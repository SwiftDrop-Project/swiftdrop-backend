-- customer module: saved pickup and drop-off addresses (the customer's "Addresses" page).
-- customer_id is the identity user id (identity.users.id), stored as a plain UUID: no foreign keys or joins
-- across schemas. Which address is the default pickup lives in customer.customer_profiles.default_pickup_address_id,
-- so a customer can only ever have one default.
CREATE TABLE customer.customer_addresses (
    id           UUID         PRIMARY KEY,
    customer_id  UUID         NOT NULL,
    label        VARCHAR(40)  NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    phone        VARCHAR(16)  NOT NULL,
    address_line VARCHAR(200) NOT NULL,
    city         VARCHAR(80)  NOT NULL,
    district     VARCHAR(30)  NOT NULL,
    postal_code  VARCHAR(5)   NOT NULL,
    landmark     VARCHAR(150),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

-- The Addresses page lists one customer's addresses, oldest first.
CREATE INDEX ix_customer_addresses_customer ON customer.customer_addresses (customer_id, created_at);
