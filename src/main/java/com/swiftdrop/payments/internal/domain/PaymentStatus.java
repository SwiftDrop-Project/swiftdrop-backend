package com.swiftdrop.payments.internal.domain;

import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelStatus;

/**
 * How the Payments page shows a delivery's fee. Whether the fee is paid is never decided here: it comes from
 * delivery's {@link FeePaymentStatus} (the one rule, shared with receipts and the courier screens).
 */
public enum PaymentStatus {
    /** Not paid yet: cash still to be collected by the courier, or an online payment still to be made. */
    DUE,
    /** Paid: delivery recorded the cash collection or the verified online (mock card) payment. */
    PAID,
    /**
     * Not paid and not expected now: the delivery failed before the fee was paid (it is due again if retried), or the
     * parcel was booked before payment methods, so nothing is recorded about its fee.
     */
    NOT_CHARGED;

    /**
     * PAID when delivery says the fee is paid ({@link FeePaymentStatus#isPaid}); NOT_CHARGED for a fee that is not
     * recorded at all, or not paid while the delivery has FAILED; otherwise DUE.
     */
    public static PaymentStatus of(FeePaymentStatus feePaymentStatus, ParcelStatus parcelStatus) {
        if (feePaymentStatus.isPaid()) {
            return PAID;
        }
        if (feePaymentStatus == FeePaymentStatus.NOT_RECORDED || parcelStatus == ParcelStatus.FAILED) {
            return NOT_CHARGED;
        }
        return DUE;
    }
}
