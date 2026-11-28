package com.swiftdrop.payments.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FeePayment;
import com.swiftdrop.delivery.FeePaymentSource;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.RecordOnlinePaymentCommand;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;
import com.swiftdrop.payments.internal.dto.CardCheckResponse;
import com.swiftdrop.payments.internal.dto.CardPaymentRequest;
import com.swiftdrop.payments.internal.dto.PaymentResponse;
import com.swiftdrop.payments.internal.persistence.PaymentRepository;
import com.swiftdrop.payments.internal.provider.MockCardGateway;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paying by mock card records the payment in delivery ({@code recordOnlinePayment}), the one place that decides
 * whether a fee is paid; a declined card records nothing; delivery's refusals are 409 PAYMENT_NOT_PAYABLE.
 */
class PaymentServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Instant BOOKED = Instant.parse("2026-11-09T05:12:00Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-11-09T05:20:41Z");
    private static final DeliveryContact CONTACT =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final String NEXT_YEAR = YearMonth.now(ZoneId.of("Asia/Colombo")).plusYears(1)
            .format(DateTimeFormatter.ofPattern("MM/yy"));

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final PaymentService service = new PaymentService(payments, new PaymentSync(deliveries, payments),
            deliveries, new MockCardGateway(), mock(PlatformTransactionManager.class));

    @Test
    void anApprovedCardRecordsTheOnlinePaymentInDeliveryWithThisPaymentAsReference() {
        DeliverySnapshot due = parcel(null);
        Payment payment = payment(due);
        when(payments.findByIdAndCustomerId(payment.getId(), CUSTOMER)).thenReturn(Optional.of(payment));
        when(deliveries.findById(due.id())).thenReturn(Optional.of(due));
        when(deliveries.recordOnlinePayment(any())).thenReturn(parcel(new FeePayment(new BigDecimal("980.00"), "LKR",
                PaymentMethod.ONLINE, FeePaymentSource.ONLINE, null, null, VERIFIED_AT, "payments:" + payment.getId())));
        when(payments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        PaymentResponse paid = service.payByCard(CUSTOMER, payment.getId(), card("4242 4242 4242 4242"));

        ArgumentCaptor<RecordOnlinePaymentCommand> recorded = ArgumentCaptor.forClass(RecordOnlinePaymentCommand.class);
        verify(deliveries).recordOnlinePayment(recorded.capture());
        assertThat(recorded.getValue().parcelId()).isEqualTo(due.id());
        assertThat(recorded.getValue().externalReference()).isEqualTo("payments:" + payment.getId());
        assertThat(paid.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.cardBrand()).isEqualTo("VISA");
        assertThat(paid.cardLast4()).isEqualTo("4242");
        assertThat(paid.paidAt()).isEqualTo(VERIFIED_AT);
    }

    @Test
    void aDeclinedCardRecordsNothingInDelivery() {
        DeliverySnapshot due = parcel(null);
        Payment payment = payment(due);
        when(payments.findByIdAndCustomerId(payment.getId(), CUSTOMER)).thenReturn(Optional.of(payment));
        when(deliveries.findById(due.id())).thenReturn(Optional.of(due));

        assertThatThrownBy(() -> service.payByCard(CUSTOMER, payment.getId(), card("4000 0000 0000 0002")))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CARD_DECLINED));
        verify(deliveries, never()).recordOnlinePayment(any());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void aFeeDeliveryAlreadyHasIsNotPayable() {
        DeliverySnapshot due = parcel(null);
        Payment payment = payment(due);
        when(payments.findByIdAndCustomerId(payment.getId(), CUSTOMER)).thenReturn(Optional.of(payment));
        when(deliveries.findById(due.id())).thenReturn(Optional.of(due));
        when(deliveries.recordOnlinePayment(any())).thenThrow(new ConflictException(ErrorCode.FEE_ALREADY_COLLECTED));

        assertThatThrownBy(() -> service.payByCard(CUSTOMER, payment.getId(), card("4242 4242 4242 4242")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_NOT_PAYABLE));
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    void aFeeAlreadyVerifiedInDeliveryIsRefusedBeforeTheCardIsTried() {
        DeliverySnapshot alreadyPaid = parcel(new FeePayment(new BigDecimal("980.00"), "LKR", PaymentMethod.ONLINE,
                FeePaymentSource.ONLINE, null, null, VERIFIED_AT, "payments:" + UUID.randomUUID()));
        Payment payment = payment(alreadyPaid);
        when(payments.findByIdAndCustomerId(payment.getId(), CUSTOMER)).thenReturn(Optional.of(payment));
        when(deliveries.findById(alreadyPaid.id())).thenReturn(Optional.of(alreadyPaid));

        assertThatThrownBy(() -> service.payByCard(CUSTOMER, payment.getId(), card("4242 4242 4242 4242")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_NOT_PAYABLE));
        verify(deliveries, never()).recordOnlinePayment(any());
    }

    // ---- Create delivery with an online payment: the gateway check, then paying by the new delivery ----

    @Test
    void theGatewayCheckApprovesATestCardWithoutChargingOrStoringAnything() {
        CardCheckResponse checked = service.checkCard(card("4242 4242 4242 4242"));

        assertThat(checked.cardBrand()).isEqualTo("VISA");
        assertThat(checked.cardLast4()).isEqualTo("4242");
        verify(deliveries, never()).recordOnlinePayment(any());
        verify(payments, never()).saveAndFlush(any());
        verify(payments, never()).insertIfMissing(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theGatewayCheckStopsTheDeclineCardBeforeAnyDeliveryIsBooked() {
        assertThatThrownBy(() -> service.checkCard(card("4000 0000 0000 0002")))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CARD_DECLINED));
    }

    @Test
    void aJustBookedOnlineDeliveryIsPaidByItsParcelIdMakingItsPaymentRecordFirst() {
        DeliverySnapshot booked = parcel(null);
        Payment made = payment(booked);
        // No record yet (the Payments page never listed this parcel): the insert makes it.
        when(payments.findByParcelIdAndCustomerId(PARCEL, CUSTOMER)).thenReturn(Optional.empty(), Optional.of(made));
        when(deliveries.findById(PARCEL)).thenReturn(Optional.of(booked));
        when(deliveries.recordOnlinePayment(any())).thenReturn(parcel(new FeePayment(new BigDecimal("980.00"), "LKR",
                PaymentMethod.ONLINE, FeePaymentSource.ONLINE, null, null, VERIFIED_AT, "payments:" + made.getId())));
        when(payments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        PaymentResponse paid = service.payParcelByCard(CUSTOMER, PARCEL, card("4242 4242 4242 4242"));

        verify(payments).insertIfMissing(any(), any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<RecordOnlinePaymentCommand> recorded = ArgumentCaptor.forClass(RecordOnlinePaymentCommand.class);
        verify(deliveries).recordOnlinePayment(recorded.capture());
        assertThat(recorded.getValue().parcelId()).isEqualTo(PARCEL);
        assertThat(paid.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.cardLast4()).isEqualTo("4242");
    }

    @Test
    void someoneElsesDeliveryIsNotFoundAndNothingIsCharged() {
        when(deliveries.findById(PARCEL)).thenReturn(Optional.of(parcel(null)));

        assertThatThrownBy(() -> service.payParcelByCard(UUID.randomUUID(), PARCEL, card("4242 4242 4242 4242")))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(deliveries, never()).recordOnlinePayment(any());
    }

    private static final UUID PARCEL = UUID.randomUUID();

    private static DeliverySnapshot parcel(FeePayment feePayment) {
        return new DeliverySnapshot(PARCEL, "SD2611003152", ParcelStatus.CREATED, CUSTOMER, null, CONTACT, CONTACT,
                new BigDecimal("2.00"), null, null, null, null, BOOKED, BOOKED, null, false, null, null,
                new BigDecimal("980.00"), "LKR", 1L, null, null, null, null, null, PaymentMethod.ONLINE, null,
                feePayment);
    }

    private static Payment payment(DeliverySnapshot parcel) {
        return new Payment(UUID.randomUUID(), parcel.id(), CUSTOMER, parcel.trackingNumber(), parcel.createdAt(),
                parcel.paymentMethod(), parcel.feeAmount(), parcel.currency(), PaymentStatus.DUE);
    }

    private static CardPaymentRequest card(String number) {
        return new CardPaymentRequest(number, NEXT_YEAR, "123", "Nimali Perera");
    }
}
