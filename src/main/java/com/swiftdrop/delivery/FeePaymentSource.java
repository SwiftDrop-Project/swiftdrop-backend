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
     * A verified online payment, with the payment provider's reference. Reserved for the payments work: nothing records
     * it yet, and choosing {@link PaymentMethod#ONLINE} at booking never does.
     */
    ONLINE
}
