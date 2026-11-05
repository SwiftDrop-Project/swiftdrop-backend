/**
 * Delivery: parcels and deliveries: creation, status changes, proof of delivery, receipts, and the outbox.
 *
 * <p>Public API: {@link com.swiftdrop.delivery.DeliveryApi}, {@link com.swiftdrop.delivery.DeliverySnapshot},
 * {@link com.swiftdrop.delivery.DeliveryContact}, {@link com.swiftdrop.delivery.ChangeStatusCommand},
 * {@link com.swiftdrop.delivery.ParcelStatus} (with the allowed transitions),
 * {@link com.swiftdrop.delivery.FailureReason}, {@link com.swiftdrop.delivery.ParcelStatusEntry},
 * {@link com.swiftdrop.delivery.ProofPhotoRef}, {@link com.swiftdrop.delivery.ProofPhoto},
 * {@link com.swiftdrop.delivery.ParcelStatusChanged}, {@link com.swiftdrop.delivery.CreateDeliveryCommand},
 * {@link com.swiftdrop.delivery.DeliveryOutboxApi}.
 *
 * <p>The assigned courier is stored on the parcel by this module (dispatch owns no schema).
 *
 * <p>Owner: TBD. Database schema: delivery (including the outbox_events table, added later). Events published: {@link com.swiftdrop.delivery.ParcelStatusChanged} (written to the outbox, relayed to RabbitMQ exchange {@code parcel.events}, routing key {@code parcel.status.changed}). Events consumed: none.
 *
 * <p>Not built yet: the {@code DeliveryApi} methods for couriers were agreed with WP9 (courier); the implementation
 * is pending (WP3). Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Delivery")
package com.swiftdrop.delivery;

import org.springframework.modulith.ApplicationModule;
