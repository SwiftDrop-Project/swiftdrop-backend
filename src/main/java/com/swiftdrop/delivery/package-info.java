/**
 * Delivery: parcels and deliveries: creation, status changes, proof of delivery, receipts, and the outbox.
 *
 * <p>Owner: TBD. Database schema: delivery (including the outbox_events table, added later). Events published: {@link com.swiftdrop.delivery.ParcelStatusChanged} (written to the outbox, relayed to RabbitMQ exchange {@code parcel.events}, routing key {@code parcel.status.changed}). Events consumed: none.
 *
 * <p>Not built yet. Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Delivery")
package com.swiftdrop.delivery;

import org.springframework.modulith.ApplicationModule;
