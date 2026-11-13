/**
 * Courier: the logged-in courier's tasks (dispatch assignments: PICKUP, TRANSFER between branches, final DELIVERY),
 * each holding one or more parcels. The courier records what happened to each parcel (collected, loaded, delivered,
 * failed, couldn't take it), hands parcels over at a branch, completes the task, and looks at one parcel with its
 * history. Endpoints under {@code /courier/assignments} and {@code /courier/parcels}, role COURIER only.
 *
 * <p>Multi-branch workflow: a parcel passes through several couriers and branches, so completing a task does not mean
 * the parcel was delivered. The task status and item outcomes belong to dispatch; the parcel status belongs to
 * delivery. Each outcome moves the parcel status too (see {@code internal.application.ParcelMoves}), in the same
 * transaction.
 *
 * <p>Stateless: no schema, no tables. Assignments are read and changed only through
 * {@link com.swiftdrop.dispatch.AssignmentApi}, parcels only through {@link com.swiftdrop.delivery.DeliveryApi}. A
 * courier sees only their own assignments and the parcels in them; anything else answers 404, so ids can't be probed.
 * Delivery (WP3) implements {@code DeliveryApi}; until dispatch (WP8) implements {@code AssignmentApi}, the endpoints
 * answer 503 SERVICE_UNAVAILABLE.
 *
 * <p>Uses: dispatch ({@code AssignmentApi} and its records), delivery ({@code DeliveryApi} and its records), shared,
 * and identity ({@code IdentityApi}, only for the temporary demo data in {@code internal.fake}). No module depends on
 * courier.
 *
 * <p>TEMPORARY: {@code internal.fake} holds an in-memory {@code AssignmentApi} and demo data, on only with
 * {@code SWIFTDROP_FAKE_DISPATCH=true}. Delete it when WP8 implements {@code AssignmentApi}.
 *
 * <p>Owner: Kalpani (WP9). Database schema: none. Events published: none (delivery publishes
 * {@code ParcelStatusChanged} for every status change). Events consumed: none.
 */
@ApplicationModule(displayName = "Courier")
package com.swiftdrop.courier;

import org.springframework.modulith.ApplicationModule;
