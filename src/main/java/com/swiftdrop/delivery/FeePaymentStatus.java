package com.swiftdrop.delivery;

/**
 * Where a parcel's delivery fee stands, worked out from its payment method and its recorded {@link FeePayment}. Never
 * stored: there is one rule ({@link #of}), so every module reads the same status.
 *
 * <p>Choosing {@link PaymentMethod#ONLINE} at booking is not a payment: such a parcel is
 * {@link #AWAITING_ONLINE_VERIFICATION} until a verified online payment is recorded.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 */
public enum FeePaymentStatus {

    /** AT_PICKUP or AT_DROP_OFF, not collected yet: a courier collects it in cash. */
    AWAITING_CASH_COLLECTION,

    /** A courier collected it in cash. */
    CASH_COLLECTED,

    /** ONLINE was chosen, but no verified online payment is recorded. Not paid as far as SwiftDrop knows. */
    AWAITING_ONLINE_VERIFICATION,

    /** A verified online payment is recorded. */
    ONLINE_VERIFIED,

    /** Booked before payment methods: nothing is known about the fee's payment. */
    NOT_RECORDED;

    /** The status of a parcel with this payment method and this recorded payment (null: none). */
    public static FeePaymentStatus of(PaymentMethod paymentMethod, FeePayment payment) {
        if (payment != null) {
            return payment.source() == FeePaymentSource.ONLINE ? ONLINE_VERIFIED : CASH_COLLECTED;
        }
        if (paymentMethod == null) {
            return NOT_RECORDED;
        }
        return paymentMethod == PaymentMethod.ONLINE ? AWAITING_ONLINE_VERIFICATION : AWAITING_CASH_COLLECTION;
    }

    /** True once the fee is paid (collected in cash or verified online). */
    public boolean isPaid() {
        return this == CASH_COLLECTED || this == ONLINE_VERIFIED;
    }
}
