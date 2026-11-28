/**
 * Payments: one payment record for every customer delivery, and paying online deliveries with a mock card (the
 * customer's "Payments" page).
 *
 * <p>Deliveries are read only through {@link com.swiftdrop.delivery.DeliveryApi}. A customer's records are brought up to
 * date from their parcels each time the Payments page asks for them, so nothing depends on RabbitMQ: a new parcel
 * gets its record. Whether a fee is paid is never decided here: it is delivery's
 * {@link com.swiftdrop.delivery.FeePaymentStatus} (the one rule, shared with receipts and the courier screens). Cash
 * is paid once a courier recorded it (WP9); an ONLINE fee once this module verified a mock card payment and recorded
 * it with {@link com.swiftdrop.delivery.DeliveryApi#recordOnlinePayment} in the same transaction. How the fee is paid
 * is the parcel's {@link com.swiftdrop.delivery.PaymentMethod} (ONLINE is shown as a mock card, AT_PICKUP and
 * AT_DROP_OFF as cash). No real money moves and the full card number is never stored.
 *
 * <p>Public API: none (endpoints only). Owner: WP2. Database schema: {@code payments} (table {@code payments}: one row
 * per parcel, {@code parcel_id} and {@code customer_id} are plain UUIDs, no foreign keys). Events published: none.
 * Events consumed: none. Depends on delivery and shared.
 */
@ApplicationModule(displayName = "Payments")
package com.swiftdrop.payments;

import org.springframework.modulith.ApplicationModule;
