package com.swiftdrop.payments.internal.domain;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;

/** Whether a delivery's fee has been paid. */
public enum PaymentStatus {
    /** Not paid yet: cash still to be collected by the courier, or an online payment still to be made. */
    DUE,
    /** Paid: by mock card, or collected in cash by the courier. */
    PAID,
    /** The delivery failed before the fee was collected, so nothing was charged (it is due again if retried). */
    NOT_CHARGED;

    /**
     * The status a payment has for a parcel in this state.
     * <ul>
     *   <li>ONLINE: paid once paid by card; otherwise not charged while the delivery has failed, else due.</li>
     *   <li>AT_PICKUP: the sender pays the courier at pickup, so paid from {@code PICKED_UP} on.</li>
     *   <li>AT_DROP_OFF, and parcels without a method (cash on delivery): paid once {@code DELIVERED}, not charged
     *   while {@code FAILED}.</li>
     * </ul>
     */
    public static PaymentStatus of(PaymentMethod method, ParcelStatus parcelStatus, boolean paidByCard) {
        if (method == PaymentMethod.ONLINE) {
            if (paidByCard) {
                return PAID;
            }
            return parcelStatus == ParcelStatus.FAILED ? NOT_CHARGED : DUE;
        }
        if (method == PaymentMethod.AT_PICKUP) {
            return parcelStatus == ParcelStatus.CREATED ? DUE : PAID;
        }
        return switch (parcelStatus) {
            case DELIVERED -> PAID;
            case FAILED -> NOT_CHARGED;
            default -> DUE;
        };
    }

    /** The parcel status at which the courier collects a cash fee (for the paid date), or null for online. */
    public static ParcelStatus cashCollectedAt(PaymentMethod method) {
        if (method == PaymentMethod.ONLINE) {
            return null;
        }
        return method == PaymentMethod.AT_PICKUP ? ParcelStatus.PICKED_UP : ParcelStatus.DELIVERED;
    }
}
