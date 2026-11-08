// TEMPORARY - delete when WP3 implements DeliveryApi and WP8 implements AssignmentApi.
/**
 * TEMPORARY demo data for the courier screens while delivery and dispatch have no implementation:
 * <ul>
 *   <li>{@code FakeDeliveryApi}, an in-memory {@link com.swiftdrop.delivery.DeliveryApi}, on only with
 *       {@code SWIFTDROP_FAKE_DELIVERY=true} ({@code swiftdrop.dev.fake-delivery})</li>
 *   <li>{@code FakeAssignmentApi}, an in-memory {@link com.swiftdrop.dispatch.AssignmentApi}, on only with
 *       {@code SWIFTDROP_FAKE_DISPATCH=true} ({@code swiftdrop.dev.fake-dispatch})</li>
 *   <li>{@code FakeDeliverySeeder}, when both are on: the Delgoda → Gampaha → Matara → Deniyaya demo, with one
 *       PICKUP, one TRANSFER and one DELIVERY task for {@code courier@swiftdrop.lk}</li>
 * </ul>
 * Nothing is written to the database and no events are published; a restart resets everything. A change made inside a
 * transaction is undone when the transaction rolls back.
 *
 * <p>Delete each fake when its module is implemented, together with its property in application.yml, .env.example and
 * IntegrationTest, and its tests; delete the seeder with the last one.
 */
package com.swiftdrop.courier.internal.fake;
