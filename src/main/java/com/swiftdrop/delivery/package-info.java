/**
 * Delivery: a parcel from booking to hand-over: creating it, status changes, proof of delivery, receipts, and the
 * outbox that publishes every status change.
 *
 * <p>Multi-branch workflow: a parcel passes through SwiftDrop branches ({@code AT_BRANCH}, with the branch as a
 * {@link com.swiftdrop.shared.reference.District}) and is handled by several couriers. Which courier handles which
 * step is decided by dispatch (WP8) assignments, so callers authorize status changes; the single-courier members of
 * {@code DeliveryApi} ({@code assignCourier} and the courier reads) are deprecated.
 *
 * <p>Public API: {@link com.swiftdrop.delivery.DeliveryApi} and the types it uses
 * ({@link com.swiftdrop.delivery.CreateDeliveryCommand}, {@link com.swiftdrop.delivery.ChangeStatusCommand},
 * {@link com.swiftdrop.delivery.DeliverySnapshot}, {@link com.swiftdrop.delivery.DeliveryContact},
 * {@link com.swiftdrop.delivery.ParcelStatus} with the allowed transitions,
 * {@link com.swiftdrop.delivery.ParcelStatusEntry}, {@link com.swiftdrop.delivery.FailureReason},
 * {@link com.swiftdrop.delivery.ProofPhotoRef}, {@link com.swiftdrop.delivery.ProofPhoto},
 * {@link com.swiftdrop.delivery.DeliveryStats}, and for the admin's All Parcels search (WP4)
 * {@link com.swiftdrop.delivery.ParcelSearch} and {@link com.swiftdrop.delivery.ParcelSort}); the event
 * {@link com.swiftdrop.delivery.ParcelStatusChanged};
 * and, for the outbox relay only, {@link com.swiftdrop.delivery.DeliveryOutboxApi} and
 * {@link com.swiftdrop.delivery.OutboxMessage}. Other modules may use only these top-level types.
 *
 * <p>Dependencies: pricing (PricingApi prices a new parcel) and shared. Delivery must never depend on
 * identity: identity depends on notifications, and notifications will consume {@code ParcelStatusChanged}, so that
 * would be a cycle. That is why user ids are plain UUIDs and roles are plain names (e.g. {@code COURIER}). Delivery
 * does not depend on dispatch either: dispatch and courier call delivery, never the other way round.
 *
 * <p>Owner: Pasindu (WP3); the courier methods were agreed with WP9 (courier). Database schema: {@code delivery},
 * tables {@code parcels}, {@code parcel_status_history} and {@code outbox_events} (no foreign keys; user ids from
 * other modules are plain UUIDs). Events published: {@code ParcelStatusChanged}, written to the outbox in the same
 * transaction as the change and relayed to RabbitMQ (exchange {@code parcel.events}, routing key
 * {@code parcel.status.changed}). Events consumed: none.
 *
 * <p>Status: contracts, tables and the domain model (entities, repositories, tracking numbers). Nothing implements
 * {@code DeliveryApi} yet: until that is merged, the application does not start with a bean that injects it, so
 * mock it in tests.
 */
@ApplicationModule(displayName = "Delivery")
package com.swiftdrop.delivery;

import org.springframework.modulith.ApplicationModule;
