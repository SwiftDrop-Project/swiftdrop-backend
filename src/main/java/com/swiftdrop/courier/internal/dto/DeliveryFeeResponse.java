package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierTask;
import com.swiftdrop.courier.internal.application.FeeCollection;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The parcel's delivery fee (API.md "Courier"): the amount and currency fixed at booking, how the customer chose to pay
 * it, and where its payment stands, from delivery's recorded payment.
 *
 * <p>Not the cash on delivery: {@code codAmount} is the price of the goods, collected for the sender; this is
 * SwiftDrop's own fee.
 *
 * @param amount        the fee fixed when the parcel was booked
 * @param currency      the fee's currency, e.g. {@code LKR}
 * @param paymentMethod how the fee is paid ({@code ONLINE}, {@code AT_PICKUP}, {@code AT_DROP_OFF}); null for
 *                      parcels booked before payment methods
 * @param status        where the payment stands ({@link FeePaymentStatus}); ONLINE is never paid until a verified
 *                      online payment is recorded
 * @param collectedAt   when it was collected or verified; null while unpaid
 * @param collectOn     the status change of this step that must confirm the cash collection ({@code PICKED_UP} or
 *                      {@code DELIVERED}, sent with {@code feeCollected: true}); null when nothing is collected in
 *                      this step (and always on a finished step)
 */
public record DeliveryFeeResponse(BigDecimal amount, String currency, PaymentMethod paymentMethod,
                                  FeePaymentStatus status, Instant collectedAt, ParcelStatus collectOn) {

    static DeliveryFeeResponse from(DeliverySnapshot parcel, CourierTask task) {
        return new DeliveryFeeResponse(parcel.feeAmount(), parcel.currency(), parcel.paymentMethod(),
                parcel.feePaymentStatus(), parcel.feePayment() == null ? null : parcel.feePayment().collectedAt(),
                FeeCollection.collectOn(parcel, task));
    }
}
