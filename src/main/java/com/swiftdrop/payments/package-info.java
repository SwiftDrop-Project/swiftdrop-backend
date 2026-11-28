/**
 * Payments: one payment record for every customer delivery, and paying online deliveries with a mock card (the
 * customer's "Payments" page).
 *
 * <p>Deliveries are read only through {@link com.swiftdrop.delivery.DeliveryApi}. A customer's records are brought up to
 * date from their parcels each time the Payments page asks for them, so nothing depends on RabbitMQ: a new parcel
 * gets its record, and a cash payment becomes paid when the parcel's status says the courier collected the fee.
 * How the fee is paid is the parcel's {@link com.swiftdrop.delivery.PaymentMethod} (ONLINE is shown as a mock card,
 * AT_PICKUP and AT_DROP_OFF as cash); parcels booked before payment methods count as cash on delivery. Only ONLINE
 * payments are paid here, with a test card; no real money moves and the full card number is never stored.
 *
 * <p>Public API: none (endpoints only). Owner: WP2. Database schema: {@code payments} (table {@code payments}: one row
 * per parcel, {@code parcel_id} and {@code customer_id} are plain UUIDs, no foreign keys). Events published: none.
 * Events consumed: none. Depends on delivery and shared.
 */
@ApplicationModule(displayName = "Payments")
package com.swiftdrop.payments;

import org.springframework.modulith.ApplicationModule;
