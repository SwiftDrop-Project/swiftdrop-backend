/**
 * Courier: the logged-in courier's parcels, one at a time. A dispatcher may group several parcels in one dispatch task
 * (PICKUP from senders to a branch, DELIVERY from a branch to recipients); the courier sees each parcel as its own card,
 * moves it on (collected → handed over at the branch; out for delivery → delivered, or failed → returned to the
 * branch), records "Unable to Collect" with a reason, and looks at one parcel with its history and at the history of
 * the parcels they completed. Endpoints under {@code /courier/deliveries}, role COURIER only.
 *
 * <p>A parcel is completed for the courier as soon as its own task item closes (dispatch's
 * {@code ItemOutcome.closes}); the task is completed by itself, through {@code AssignmentApi.complete}, when its last
 * parcel closes. Collected and failed parcels stay active until they are handed over or returned. Task status and item
 * outcomes belong to dispatch; the parcel status belongs to delivery; each step writes both in one transaction.
 *
 * <p>Stateless: no schema, no tables. Assignments are read and changed only through
 * {@link com.swiftdrop.dispatch.AssignmentApi}, parcels only through {@link com.swiftdrop.delivery.DeliveryApi}. A
 * courier sees only parcels that are (or were) in their own tasks; anything else answers 404, so ids can't be probed.
 * They get only the contact their step needs (sender for a pickup, recipient for a delivery) while it is open, and
 * parcel history without notes. A clash with a dispatcher's change to the same task answers 409 ASSIGNMENT_CHANGED
 * and nothing is kept.
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
