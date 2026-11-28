package com.swiftdrop.payments.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings a customer's payment records up to date with their parcels (DeliveryApi): a record for every parcel, and the
 * status its method and parcel status give. Cash becomes paid when the courier has collected it (the paid date is when
 * the parcel reached that status); a mock card payment is never undone.
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

    /** Updates one record from its parcel (also before a card payment, so its status is current). */
    void follow(DeliverySnapshot parcel, Payment payment) {
        PaymentStatus status = PaymentStatus.of(parcel.paymentMethod(), parcel.status(), payment.isPaidByCard());
        Instant collectedAt = status == PaymentStatus.PAID && payment.getStatus() != PaymentStatus.PAID
                && !payment.isPaidByCard() ? collectedAt(parcel) : null;
        payment.follow(parcel.paymentMethod(), fee(parcel), currency(parcel), status, collectedAt);
    }

    /** When the courier collected the cash: the first time the parcel reached that status. */
    private Instant collectedAt(DeliverySnapshot parcel) {
        ParcelStatus at = PaymentStatus.cashCollectedAt(parcel.paymentMethod());
        return deliveries.history(parcel.id()).stream()
                .filter(entry -> entry.status() == at)
                .map(ParcelStatusEntry::occurredAt)
                .min(Comparator.naturalOrder())
                .orElse(parcel.updatedAt());
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
