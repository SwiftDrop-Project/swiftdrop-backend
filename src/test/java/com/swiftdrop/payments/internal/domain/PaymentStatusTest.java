package com.swiftdrop.payments.internal.domain;

import com.swiftdrop.delivery.FeePaymentStatus;
import com.swiftdrop.delivery.ParcelStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.swiftdrop.delivery.FeePaymentStatus.AWAITING_CASH_COLLECTION;
import static com.swiftdrop.delivery.FeePaymentStatus.AWAITING_ONLINE_VERIFICATION;
import static com.swiftdrop.delivery.FeePaymentStatus.CASH_COLLECTED;
import static com.swiftdrop.delivery.FeePaymentStatus.NOT_RECORDED;
import static com.swiftdrop.delivery.FeePaymentStatus.ONLINE_VERIFIED;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.DUE;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.NOT_CHARGED;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.PAID;
import static org.assertj.core.api.Assertions.assertThat;

/** Paid or not comes only from delivery's FeePaymentStatus (the one rule); the parcel status only adds "not charged". */
class PaymentStatusTest {

    @ParameterizedTest
    @EnumSource(ParcelStatus.class)
    void aRecordedPaymentIsPaidWhateverTheParcelStatus(ParcelStatus parcelStatus) {
        assertThat(PaymentStatus.of(CASH_COLLECTED, parcelStatus)).isEqualTo(PAID);
        assertThat(PaymentStatus.of(ONLINE_VERIFIED, parcelStatus)).isEqualTo(PAID);
    }

    @Test
    void anAwaitedFeeIsDueUntilDeliveryRecordsItEvenAfterPickupOrDelivery() {
        // The parcel status alone never makes a fee paid: a courier (or the mock card) must record it.
        for (FeePaymentStatus awaited : new FeePaymentStatus[] {AWAITING_CASH_COLLECTION, AWAITING_ONLINE_VERIFICATION}) {
            assertThat(PaymentStatus.of(awaited, CREATED)).isEqualTo(DUE);
            assertThat(PaymentStatus.of(awaited, PICKED_UP)).isEqualTo(DUE);
            assertThat(PaymentStatus.of(awaited, DELIVERED)).isEqualTo(DUE);
        }
    }

    @Test
    void anUnpaidFeeOfAFailedDeliveryIsNotCharged() {
        assertThat(PaymentStatus.of(AWAITING_CASH_COLLECTION, FAILED)).isEqualTo(NOT_CHARGED);
        assertThat(PaymentStatus.of(AWAITING_ONLINE_VERIFICATION, FAILED)).isEqualTo(NOT_CHARGED);
    }

    @ParameterizedTest
    @EnumSource(ParcelStatus.class)
    void aParcelBookedBeforePaymentMethodsIsNotCharged(ParcelStatus parcelStatus) {
        assertThat(PaymentStatus.of(NOT_RECORDED, parcelStatus)).isEqualTo(NOT_CHARGED);
    }
}
