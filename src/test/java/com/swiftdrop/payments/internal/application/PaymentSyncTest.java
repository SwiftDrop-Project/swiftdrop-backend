package com.swiftdrop.payments.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;
import com.swiftdrop.payments.internal.persistence.PaymentRepository;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Records follow the parcels: one per parcel, and the status and paid date their state gives. */
class PaymentSyncTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant BOOKED = Instant.parse("2026-11-09T05:12:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-11-10T08:30:00Z");
    private static final DeliveryContact CONTACT =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);

    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentSync sync = new PaymentSync(deliveries, payments);

    @Test
    void aParcelWithoutARecordGetsOneThatIsDue() {
        DeliverySnapshot parcel = parcel(PaymentMethod.ONLINE, ParcelStatus.CREATED);
        parcels(parcel);
        Payment made = payment(parcel, PaymentStatus.DUE);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(), List.of(made));

        sync.syncCustomer(CUSTOMER);

        verify(payments).insertIfMissing(any(), eq(parcel.id()), eq(CUSTOMER), eq("SD2611003152"), eq(BOOKED),
                eq("ONLINE"), eq(new BigDecimal("980.00")), eq("LKR"), eq("DUE"));
        assertThat(made.getStatus()).isEqualTo(PaymentStatus.DUE);
    }

    @Test
    void aParcelThatAlreadyHasARecordIsNotInsertedAgain() {
        DeliverySnapshot parcel = parcel(PaymentMethod.ONLINE, ParcelStatus.CREATED);
        parcels(parcel);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(payment(parcel, PaymentStatus.DUE)));

        sync.syncCustomer(CUSTOMER);

        verify(payments, never()).insertIfMissing(any(), any(), any(), anyString(), any(), any(), any(), anyString(),
                anyString());
    }

    @Test
    void cashIsPaidOnlyOnceACourierRecordedItWithTheTimeDeliveryRecorded() {
        DeliverySnapshot delivered = parcel(PaymentMethod.AT_DROP_OFF, ParcelStatus.DELIVERED);
        DeliverySnapshot collected = parcel(PaymentMethod.AT_DROP_OFF, ParcelStatus.DELIVERED,
                new FeePayment(new BigDecimal("980.00"), "LKR", PaymentMethod.AT_DROP_OFF,
                        FeePaymentSource.COURIER_CASH, UUID.randomUUID(), UUID.randomUUID(), DELIVERED_AT));
        parcels(delivered, collected);
        Payment notRecorded = payment(delivered, PaymentStatus.DUE);
        Payment recorded = payment(collected, PaymentStatus.DUE);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(notRecorded, recorded));

        sync.syncCustomer(CUSTOMER);

        // Delivered but no collection recorded by the courier: still due (one rule: delivery's feePaymentStatus).
        assertThat(notRecorded.getStatus()).isEqualTo(PaymentStatus.DUE);
        assertThat(notRecorded.getPaidAt()).isNull();
        assertThat(recorded.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(recorded.getPaidAt()).isEqualTo(DELIVERED_AT);
    }

    @Test
    void anOnlinePaymentIsPaidWhenDeliveryHasTheVerifiedPayment() {
        Instant verifiedAt = Instant.parse("2026-11-09T05:20:00Z");
        DeliverySnapshot parcel = parcel(PaymentMethod.ONLINE, ParcelStatus.CREATED,
                new FeePayment(new BigDecimal("980.00"), "LKR", PaymentMethod.ONLINE, FeePaymentSource.ONLINE, null,
                        null, verifiedAt, "payments:" + UUID.randomUUID()));
        parcels(parcel);
        Payment payment = payment(parcel, PaymentStatus.DUE);
        payment.payByCard("VISA", "4242", verifiedAt);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(payment));

        sync.syncCustomer(CUSTOMER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(verifiedAt);
        assertThat(payment.getCardLast4()).isEqualTo("4242");
    }

    @Test
    void anUnpaidFeeOfAFailedDeliveryIsNotChargedAndHasNoPaidDate() {
        DeliverySnapshot parcel = parcel(PaymentMethod.AT_DROP_OFF, ParcelStatus.FAILED);
        parcels(parcel);
        Payment payment = payment(parcel, PaymentStatus.DUE);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(payment));

        sync.syncCustomer(CUSTOMER);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NOT_CHARGED);
        assertThat(payment.getPaidAt()).isNull();
    }

    @Test
    void aParcelBookedBeforePaymentMethodsIsNotCharged() {
        DeliverySnapshot parcel = parcel(null, ParcelStatus.DELIVERED);
        parcels(parcel);
        Payment payment = payment(parcel, PaymentStatus.DUE);
        when(payments.findByCustomerId(CUSTOMER)).thenReturn(List.of(payment));

        sync.syncCustomer(CUSTOMER);

        assertThat(parcel.feePaymentStatus()).isEqualTo(FeePaymentStatus.NOT_RECORDED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NOT_CHARGED);
    }

    private void parcels(DeliverySnapshot... parcels) {
        when(deliveries.findByCustomer(eq(CUSTOMER), isNull(ParcelStatus.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(parcels)));
    }

    private static DeliverySnapshot parcel(PaymentMethod method, ParcelStatus status) {
        return parcel(method, status, null);
    }

    /** A snapshot as delivery gives it: feePaymentStatus worked out from the method and the recorded payment. */
    private static DeliverySnapshot parcel(PaymentMethod method, ParcelStatus status, FeePayment feePayment) {
        return new DeliverySnapshot(UUID.randomUUID(), "SD2611003152", status, CUSTOMER, null, CONTACT, CONTACT,
                new BigDecimal("2.00"), null, null, null, null, BOOKED, BOOKED, null, false, null, null,
                new BigDecimal("980.00"), "LKR", 1L, null, null, null, null, null, method, null, feePayment);
    }

    private static Payment payment(DeliverySnapshot parcel, PaymentStatus status) {
        return new Payment(UUID.randomUUID(), parcel.id(), CUSTOMER, parcel.trackingNumber(), parcel.createdAt(),
                parcel.paymentMethod(), parcel.feeAmount(), parcel.currency(), status);
    }
}
