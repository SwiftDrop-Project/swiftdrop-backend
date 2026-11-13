// TEMPORARY - delete when WP8 implements AssignmentApi.
/**
 * TEMPORARY demo data for the courier screens while dispatch has no implementation, on only with
 * {@code SWIFTDROP_FAKE_DISPATCH=true} ({@code swiftdrop.dev.fake-dispatch}):
 * <ul>
 *   <li>{@code FakeAssignmentApi}, an in-memory {@link com.swiftdrop.dispatch.AssignmentApi}. Nothing is written to
 *       the database; a restart forgets the tasks. A change made inside a transaction is undone when the transaction
 *       rolls back.</li>
 *   <li>{@code DemoCourierSeeder}: on a local database only, creates real demo parcels through
 *       {@link com.swiftdrop.delivery.DeliveryApi} on every start and gives {@code courier@swiftdrop.lk} the
 *       Delgoda → Gampaha → Matara → Deniyaya demo (one PICKUP, one TRANSFER and one DELIVERY task).</li>
 * </ul>
 *
 * <p>Delete the package when WP8 implements AssignmentApi, together with the property in application.yml,
 * .env.example and IntegrationTest, and the tests that use it.
 */
package com.swiftdrop.courier.internal.fake;
