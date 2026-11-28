package com.swiftdrop.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The recorded payment of a parcel's delivery fee: at most one per parcel. The amount and currency are the parcel's
 * own fee, copied when the payment was recorded (never sent by a client).
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 *
 * @param paymentMethod how the fee was to be paid (the parcel's payment method when it was paid)
 * @param source        cash collected by a courier, or a verified online payment
 * @param collectedBy   the courier who collected it (an identity user id); null for an online payment
 * @param assignmentId  the dispatch task it was collected in; null for an online payment
 * @param collectedAt       when it was collected or verified
 * @param externalReference for a verified online payment, its reference in the module that verified it (see
 *                          {@link RecordOnlinePaymentCommand}); null for cash
 */
public record FeePayment(
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        FeePaymentSource source,
        UUID collectedBy,
        UUID assignmentId,
        Instant collectedAt,
        String externalReference) {

    /** A payment without an external reference (cash). Kept so callers written before online payments compile. */
    public FeePayment(BigDecimal amount, String currency, PaymentMethod paymentMethod, FeePaymentSource source,
                      UUID collectedBy, UUID assignmentId, Instant collectedAt) {
        this(amount, currency, paymentMethod, source, collectedBy, assignmentId, collectedAt, null);
    }
}
