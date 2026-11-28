package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeePaymentStatusTest {

    private static FeePayment payment(PaymentMethod method, FeePaymentSource source) {
        return new FeePayment(new BigDecimal("450.00"), "LKR", method, source,
                source == FeePaymentSource.COURIER_CASH ? UUID.randomUUID() : null,
                source == FeePaymentSource.COURIER_CASH ? UUID.randomUUID() : null, Instant.now());
    }

    @Test
    void cashFeesAwaitCollectionUntilACourierCollectsThem() {
        assertThat(FeePaymentStatus.of(PaymentMethod.AT_PICKUP, null)).isEqualTo(FeePaymentStatus.AWAITING_CASH_COLLECTION);
        assertThat(FeePaymentStatus.of(PaymentMethod.AT_DROP_OFF, null))
                .isEqualTo(FeePaymentStatus.AWAITING_CASH_COLLECTION);
        assertThat(FeePaymentStatus.of(PaymentMethod.AT_PICKUP, payment(PaymentMethod.AT_PICKUP,
                FeePaymentSource.COURIER_CASH))).isEqualTo(FeePaymentStatus.CASH_COLLECTED);
    }

    @Test
    void choosingOnlineIsNeverAPayment() {
        assertThat(FeePaymentStatus.of(PaymentMethod.ONLINE, null))
                .isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);
        assertThat(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION.isPaid()).isFalse();
        assertThat(FeePaymentStatus.of(PaymentMethod.ONLINE, payment(PaymentMethod.ONLINE, FeePaymentSource.ONLINE)))
                .isEqualTo(FeePaymentStatus.ONLINE_VERIFIED);
        assertThat(FeePaymentStatus.ONLINE_VERIFIED.isPaid()).isTrue();
    }

    @Test
    void olderParcelsAreNotRecorded() {
        assertThat(FeePaymentStatus.of(null, null)).isEqualTo(FeePaymentStatus.NOT_RECORDED);
        assertThat(FeePaymentStatus.NOT_RECORDED.isPaid()).isFalse();
    }

    @Test
    void aSnapshotWorksItsStatusOutWhenNotGiven() {
        DeliverySnapshot unpaid = new DeliverySnapshot(UUID.randomUUID(), "SD2611000001", ParcelStatus.CREATED, null,
                UUID.randomUUID(), null, null, BigDecimal.ONE, null, null, null, null, Instant.now(), Instant.now(),
                null, false, null, null, new BigDecimal("450.00"), "LKR", 0, null, null, null, null, null,
                PaymentMethod.ONLINE);
        assertThat(unpaid.feePaymentStatus()).isEqualTo(FeePaymentStatus.AWAITING_ONLINE_VERIFICATION);
        assertThat(unpaid.feePayment()).isNull();
    }
}
