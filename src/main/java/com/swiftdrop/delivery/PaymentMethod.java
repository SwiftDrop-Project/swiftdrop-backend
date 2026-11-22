package com.swiftdrop.delivery;

/**
 * How the delivery fee is paid, chosen when the parcel is booked (a customer booking or a merchant order). The courier
 * uses it to know whether to collect the fee, and from whom.
 *
 * <p>It is not the cash on delivery ({@code codAmount}): that is the price of the goods, which the courier collects
 * from the recipient for the sender. Whether an online payment went through is not kept here either: the payments
 * work owns that.
 *
 * <p>Contract owned by delivery.
 */
public enum PaymentMethod {

    /** Paid online (card or online banking). The courier collects no fee. */
    ONLINE,

    /** The sender pays the courier in cash when the parcel is picked up. */
    AT_PICKUP,

    /** The recipient pays the courier in cash when the parcel is delivered, together with any cash on delivery. */
    AT_DROP_OFF
}
