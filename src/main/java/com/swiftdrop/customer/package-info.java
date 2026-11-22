/**
 * Customer module: customer profile, delivery preferences, saved addresses, delivery history and payments.
 *
 * <p>Customer details combine account data from {@link com.swiftdrop.identity.IdentityApi} with customer-specific
 * preferences stored in the {@code customer} schema. Delivery counts come from WP3's parcel service
 * ({@link com.swiftdrop.delivery.DeliveryApi}); the customer module stores no parcels of its own.
 *
 * <p>Public API: {@link com.swiftdrop.customer.CustomerApi}, {@link com.swiftdrop.customer.CustomerSummary},
 * {@link com.swiftdrop.customer.PaymentMethod}.
 *
 * <p>Owner: WP2. Database schema: {@code customer} (tables {@code customer_profiles}, keyed by the identity user id,
 * and {@code customer_addresses}, the saved addresses, with {@code customer_id} = identity user id; no foreign keys).
 * The default pickup address is {@code customer_profiles.default_pickup_address_id}. Events published: none yet.
 * Events consumed: none.
 */
@ApplicationModule(displayName = "Customer")
package com.swiftdrop.customer;

import org.springframework.modulith.ApplicationModule;
