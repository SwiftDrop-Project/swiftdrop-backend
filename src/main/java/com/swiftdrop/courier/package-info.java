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
 * Both APIs come by constructor injection. A clash with a dispatcher's change to the same task answers 409
 * ASSIGNMENT_CHANGED (dispatch's optimistic lock) and the parcel change is rolled back.
 *
 * <p>Tasks scheduled for a later day ({@code scheduledFor}) are listed and can be worked now; the date is information
 * only, as dispatch defines it.
 *
 * <p>Uses: dispatch ({@code AssignmentApi} and its records), delivery ({@code DeliveryApi} and its records) and
 * shared. No module depends on courier.
 *
 * <p>Owner: Kalpani (WP9). Database schema: none. Events published: none (delivery publishes
 * {@code ParcelStatusChanged} for every status change). Events consumed: none.
 */
@ApplicationModule(displayName = "Courier")
package com.swiftdrop.courier;

import org.springframework.modulith.ApplicationModule;
