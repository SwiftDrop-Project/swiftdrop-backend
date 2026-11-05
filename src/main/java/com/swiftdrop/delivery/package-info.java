/**
 * Delivery: a parcel from booking to hand-over: creating it, assigning a courier, status changes, proof of delivery,
 * receipts, and the outbox that publishes every status change.
 *
 * <p>Public API: {@link com.swiftdrop.delivery.DeliveryApi} and the types it uses
 * ({@link com.swiftdrop.delivery.CreateDeliveryCommand}, {@link com.swiftdrop.delivery.ChangeStatusCommand},
 * {@link com.swiftdrop.delivery.DeliverySnapshot}, {@link com.swiftdrop.delivery.DeliveryContact},
 * {@link com.swiftdrop.delivery.ParcelStatus} with the allowed transitions,
 * {@link com.swiftdrop.delivery.ParcelStatusEntry}, {@link com.swiftdrop.delivery.FailureReason},
 * {@link com.swiftdrop.delivery.ProofPhotoRef}, {@link com.swiftdrop.delivery.ProofPhoto},
 * {@link com.swiftdrop.delivery.DeliveryStats}); the event {@link com.swiftdrop.delivery.ParcelStatusChanged};
 * and, for the outbox relay only, {@link com.swiftdrop.delivery.DeliveryOutboxApi} and
 * {@link com.swiftdrop.delivery.OutboxMessage}. Other modules may use only these top-level types.
 *
 * <p>Dependencies: shared only (pricing will be added, to price a new parcel). Delivery must never depend on
 * identity: identity depends on notifications, and notifications will consume {@code ParcelStatusChanged}, so that
 * would be a cycle. That is why user ids are plain UUIDs and roles are plain names (e.g. {@code COURIER}), and why
 * dispatch checks that a user is an active courier (through identity) before it calls {@code assignCourier}. The
 * assigned courier is stored on the parcel by this module, because dispatch owns no schema.
 *
 * <p>Owner: Pasindu (WP3); the courier methods were agreed with WP9 (courier). Database schema: {@code delivery}
 * (no foreign keys; user ids from other modules are plain UUIDs). Events published: {@code ParcelStatusChanged},
 * written to the outbox in the same transaction as the change and relayed to RabbitMQ (exchange
 * {@code parcel.events}, routing key {@code parcel.status.changed}). Events consumed: none.
 *
 * <p>Status: contracts only. Nothing implements {@code DeliveryApi} yet: until that is merged, the application does
 * not start with a bean that injects it, so mock it in tests.
 */
@ApplicationModule(displayName = "Delivery")
package com.swiftdrop.delivery;

import org.springframework.modulith.ApplicationModule;
