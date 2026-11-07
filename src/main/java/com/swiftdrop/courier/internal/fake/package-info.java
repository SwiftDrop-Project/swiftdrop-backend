// TEMPORARY - delete when WP3 implements DeliveryApi.
/**
 * TEMPORARY demo data for the courier screens while delivery has no implementation: an in-memory
 * {@link com.swiftdrop.delivery.DeliveryApi} and a seeder, on only with {@code SWIFTDROP_FAKE_DELIVERY=true}
 * ({@code swiftdrop.dev.fake-delivery}). Nothing is written to the database and no events are published; a restart
 * resets the parcels.
 *
 * <p>Delete this package when WP3 implements {@code DeliveryApi}, together with the property in application.yml,
 * .env.example and IntegrationTest, and the fake's tests.
 */
package com.swiftdrop.courier.internal.fake;
