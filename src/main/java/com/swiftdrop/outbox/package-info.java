/**
 * Outbox: relays delivery's outbox events to RabbitMQ, reading the outbox only through {@code DeliveryOutboxApi}.
 *
 * <p>Owner: TBD. Database schema: none (reads delivery.outbox_events through DeliveryOutboxApi). Events published: {@code ParcelStatusChanged} to RabbitMQ exchange {@code parcel.events}. Events consumed: none (polls the outbox).
 *
 * <p>Not built yet. Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Outbox")
package com.swiftdrop.outbox;

import org.springframework.modulith.ApplicationModule;
