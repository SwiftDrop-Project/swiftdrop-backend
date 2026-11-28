package com.swiftdrop.delivery;

/**
 * How a parcel's delivery fee was paid, on a recorded {@link FeePayment}.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 */
public enum FeePaymentSource {

    /** A courier collected the fee in cash: from the sender at pickup, or from the recipient at delivery. */
    COURIER_CASH,

    /**
     * A verified online payment, with its external reference, recorded by the payments module (WP2) through
     * {@link DeliveryApi#recordOnlinePayment} once it has verified the payment. Today that verification is WP2's mock
     * card gateway (academic demonstration, not a real payment provider). Choosing {@link PaymentMethod#ONLINE} at
     * booking never records it.
     */
    ONLINE
}
