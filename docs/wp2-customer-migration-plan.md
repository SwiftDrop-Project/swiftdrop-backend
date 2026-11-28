# WP2 Customer: migration plan

What WP2 (customer profile, addresses, history, receipts, payments) stores, in which schema, and in which Flyway
migrations. The API contract is in `API.md` ("Customer" → "WP2 contract at a glance"). Rules from `CLAUDE.md` and the
team guide apply: one schema per stateful module, no foreign keys, other modules' rows referred to by UUID only,
additive changes only, and a migration is never edited after it has run anywhere.

## 1. Schemas and ownership

| Schema | Module | Tables | Why a table |
|---|---|---|---|
| `customer` | `customer` | `customer_profiles`, `customer_addresses` | Data only the customer owns: preferences and saved addresses |
| `payments` | `payments` | `payments` | One payment record per delivery, and mock card payments (ER diagram "Payment") |

No table, by design:

- **Delivery history**: read from WP3's parcels through `DeliveryApi.findByCustomer` (status tabs and tracking number
  search). WP2 keeps no copy of parcels.
- **Receipts** (JSON and PDF): built on request from the parcel and its stored fee lines (`delivery.parcels`), so a
  receipt always shows the fee fixed at booking.
- **Customer identity** (name, email, phone): stays in `identity.users`; the customer module reads it through
  `IdentityApi` and changes the name and phone only through `IdentityApi.updateContact`.

## 2. Migrations, in order

Flyway orders versions across all module folders (`out-of-order: true`), so each file sorts after the ones it builds
on. All three have **already run on the shared Neon database**, so all three are frozen: any change is a new
migration.

| # | File | Creates | On Neon |
|---|---|---|---|
| 1 | `customer/V202611212050__customer_profile_baseline.sql` | schema `customer`; table `customer_profiles` | 2026-11-22 06:12 UTC |
| 2 | `customer/V202611222030__customer_addresses.sql` | table `customer_addresses` + index | 2026-11-22 15:52 UTC |
| 3 | `payments/V202611280400__payments_baseline.sql` | schema `payments`; table `payments` + index | 2026-11-28 03:28 UTC |

Each module's first migration starts with `CREATE SCHEMA IF NOT EXISTS <schema>;` (files 1 and 3).

## 3. Tables

All ids are UUIDs, all times `TIMESTAMPTZ` (UTC), money `NUMERIC(12, 2)` in LKR, phones stored as `+947XXXXXXXX`.

### `customer.customer_profiles` (file 1)

| Column | Type | Notes |
|---|---|---|
| `user_id` | UUID, primary key | `identity.users.id` (no foreign key). Created on the first profile request |
| `default_pickup_address_id` | UUID, null | One of the customer's `customer_addresses.id`; the service checks it is the customer's own |
| `preferred_payment_method` | VARCHAR(30), not null, default `CASH_ON_DELIVERY` | Check: `CASH_ON_DELIVERY`, `MOCK_CARD` |
| `created_at`, `updated_at` | TIMESTAMPTZ, not null | |

### `customer.customer_addresses` (file 2)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID, primary key | |
| `customer_id` | UUID, not null | `identity.users.id` (no foreign key) |
| `label` | VARCHAR(40), not null | "Home", "Shop" |
| `contact_name` | VARCHAR(100), not null | |
| `phone` | VARCHAR(16), not null | Normalised `+947XXXXXXXX` |
| `address_line` | VARCHAR(200), not null | |
| `city` | VARCHAR(80), not null | Needed so an address can fill a delivery's pickup or recipient |
| `district` | VARCHAR(30), not null | One of the 25 `District` codes |
| `postal_code` | VARCHAR(5), not null | 5 digits |
| `landmark` | VARCHAR(150), null | |
| `created_at`, `updated_at` | TIMESTAMPTZ, not null | |

Index `ix_customer_addresses_customer (customer_id, created_at)` for the Addresses page. The default address is not a
column here: it is `customer_profiles.default_pickup_address_id`, so a customer can only ever have one. The limit of 20
addresses per customer is a service rule (`409 ADDRESS_LIMIT_REACHED`), not a constraint.

### `payments.payments` (file 3)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID, primary key | |
| `parcel_id` | UUID, not null, **unique** | `delivery.parcels.id` (no foreign key); one record per delivery |
| `customer_id` | UUID, not null | `identity.users.id` |
| `tracking_number`, `booked_at` | VARCHAR(20) / TIMESTAMPTZ, not null | Copied from the parcel so the page sorts and pages in this table alone |
| `method` | VARCHAR(20), null | The parcel's `PaymentMethod`: `ONLINE`, `AT_PICKUP`, `AT_DROP_OFF`; null for parcels booked before payment methods (cash on delivery) |
| `amount`, `currency` | NUMERIC(12,2) ≥ 0 / VARCHAR(3), not null | The parcel's fee |
| `status` | VARCHAR(20), not null | `DUE`, `PAID`, `NOT_CHARGED` (rules in API.md "Payments") |
| `card_brand`, `card_last4` | VARCHAR(20) / VARCHAR(4), null | Only after a mock card payment. The full card number, expiry and security code are never stored |
| `paid_at` | TIMESTAMPTZ, null | When paid (cash: when the parcel reached `PICKED_UP` / `DELIVERED`) |
| `created_at`, `updated_at` | TIMESTAMPTZ, not null | |
| `version` | BIGINT, not null, default 0 | Optimistic locking: two card payments at once, the second gets `409` |

Index `ix_payments_customer (customer_id, booked_at DESC, id DESC)` for the Payments page. Records are created and
updated from the customer's parcels whenever the Payments page asks (`INSERT ... ON CONFLICT (parcel_id) DO NOTHING`),
so nothing depends on RabbitMQ.

## 4. Changing this later

- Never edit files 1-3. Add a new file in the same folder, timestamped with the current date and time
  (`V<yyyyMMddHHmm>__<description>.sql`), sorting after the one it changes.
- Additive only: add a column (nullable, or with a default) or a table; to rename, add the new column, copy the data,
  stop using the old one.
- No foreign keys, not even inside one schema (`DatabaseIsolationTest`).
- The entity must match the migration exactly (`ddl-auto: validate`), or the backend will not start.
- Test a new migration on your own database first (local PostgreSQL or Testcontainers), never first on Neon: once it
  has run there it is frozen for all 9 developers.

## 5. Demo data

None is seeded yet. If the demo needs it, add a seeder in the `customer` / `payments` module that runs only when
`SEED_DEMO_USERS=true`, is idempotent and is safe when several backends start at once (catch the duplicate, log,
continue). Payments need no seeding: they follow the demo customer's deliveries.
