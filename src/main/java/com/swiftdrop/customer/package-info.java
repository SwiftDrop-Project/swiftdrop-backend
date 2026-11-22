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
 * <p>Owner: WP2. Database schema: {@code customer} (table {@code customer_profiles}; {@code user_id} is identity
 * user id, no foreign keys). Events published: none yet. Events consumed: none.
 */
@ApplicationModule(displayName = "Customer")
package com.swiftdrop.customer;

import org.springframework.modulith.ApplicationModule;
