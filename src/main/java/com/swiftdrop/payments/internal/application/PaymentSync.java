package com.swiftdrop.payments.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;
import com.swiftdrop.payments.internal.persistence.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings a customer's payment records up to date with their parcels (DeliveryApi): a record for every parcel, with
 * the status delivery's {@code feePaymentStatus} gives. Cash is paid once a courier recorded the collection
 * (WP9, {@code recordFeeCollection}); online once this module recorded a mock card payment
 * ({@code recordOnlinePayment}). The paid date is the one delivery recorded.
 */
@Component
class PaymentSync {

    /** DeliveryApi.findByCustomer's largest page. */
    private static final int PARCEL_PAGE = 100;
    private static final String DEFAULT_CURRENCY = "LKR";

    private final DeliveryApi deliveries;
    private final PaymentRepository payments;

    PaymentSync(DeliveryApi deliveries, PaymentRepository payments) {
        this.deliveries = deliveries;
        this.payments = payments;
    }

    @Transactional
    public void syncCustomer(UUID customerId) {
        List<DeliverySnapshot> parcels = allParcels(customerId);
        Map<UUID, Payment> byParcel = byParcel(payments.findByCustomerId(customerId));

        boolean inserted = false;
        for (DeliverySnapshot parcel : parcels) {
            if (!byParcel.containsKey(parcel.id())) {
                payments.insertIfMissing(UUID.randomUUID(), parcel.id(), customerId, parcel.trackingNumber(),
                        parcel.createdAt(), parcel.paymentMethod() == null ? null : parcel.paymentMethod().name(),
                        fee(parcel), currency(parcel), PaymentStatus.DUE.name());
                inserted = true;
            }
        }
        if (inserted) {
            byParcel = byParcel(payments.findByCustomerId(customerId));
        }
        for (DeliverySnapshot parcel : parcels) {
            Payment payment = byParcel.get(parcel.id());
            if (payment != null) {
                follow(parcel, payment);
            }
        }
    }

    /**
     * The record of one parcel, made now if it has none yet (a delivery just booked online and paid on the spot,
     * before the Payments page ever listed it), and brought up to date with the parcel. Runs in the caller's
     * transaction; two requests at once still make one row (the insert does nothing for the second).
     */
    Payment recordFor(UUID customerId, DeliverySnapshot parcel) {
        Payment payment = payments.findByParcelIdAndCustomerId(parcel.id(), customerId).orElseGet(() -> {
            payments.insertIfMissing(UUID.randomUUID(), parcel.id(), customerId, parcel.trackingNumber(),
                    parcel.createdAt(), parcel.paymentMethod() == null ? null : parcel.paymentMethod().name(),
                    fee(parcel), currency(parcel), PaymentStatus.DUE.name());
            return payments.findByParcelIdAndCustomerId(parcel.id(), customerId).orElseThrow();
        });
        follow(parcel, payment);
        return payment;
    }

    /**
     * Updates one record from its parcel (also before a card payment, so its status is current). Paid or not comes
     * only from delivery's {@code feePaymentStatus}; a paid fee takes the amount and time delivery recorded.
     */
    void follow(DeliverySnapshot parcel, Payment payment) {
        PaymentStatus status = PaymentStatus.of(parcel.feePaymentStatus(), parcel.status());
        FeePayment recorded = parcel.feePayment();
        BigDecimal amount = recorded != null && recorded.amount() != null ? recorded.amount() : fee(parcel);
        String currency = recorded != null && recorded.currency() != null ? recorded.currency() : currency(parcel);
        Instant paidAt = recorded != null ? recorded.collectedAt() : null;
        payment.follow(parcel.paymentMethod(), amount, currency, status, paidAt);
    }

    private List<DeliverySnapshot> allParcels(UUID customerId) {
        List<DeliverySnapshot> all = new ArrayList<>();
        ParcelStatus everyStatus = null;
        Page<DeliverySnapshot> page;
        int number = 0;
        do {
            page = deliveries.findByCustomer(customerId, everyStatus, PageRequest.of(number++, PARCEL_PAGE));
            all.addAll(page.getContent());
        } while (page.hasNext());
        return all;
    }

    private static Map<UUID, Payment> byParcel(List<Payment> list) {
        return list.stream().collect(Collectors.toMap(Payment::getParcelId, Function.identity()));
    }

    private static BigDecimal fee(DeliverySnapshot parcel) {
        return parcel.feeAmount() == null ? BigDecimal.ZERO : parcel.feeAmount();
    }

    private static String currency(DeliverySnapshot parcel) {
        return parcel.currency() == null ? DEFAULT_CURRENCY : parcel.currency();
    }
}
