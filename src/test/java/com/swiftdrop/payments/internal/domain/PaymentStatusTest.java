package com.swiftdrop.payments.internal.domain;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.DUE;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.NOT_CHARGED;
import static com.swiftdrop.payments.internal.domain.PaymentStatus.PAID;
import static org.assertj.core.api.Assertions.assertThat;

/** When a delivery's fee counts as paid, due or not charged (API.md "Payments"). */
class PaymentStatusTest {

    @Test
    void anOnlinePaymentIsDueUntilPaidByCardAndNotChargedWhileTheDeliveryHasFailed() {
        assertThat(PaymentStatus.of(PaymentMethod.ONLINE, CREATED, false)).isEqualTo(DUE);
        assertThat(PaymentStatus.of(PaymentMethod.ONLINE, DELIVERED, false)).isEqualTo(DUE);
        assertThat(PaymentStatus.of(PaymentMethod.ONLINE, FAILED, false)).isEqualTo(NOT_CHARGED);
        assertThat(PaymentStatus.of(PaymentMethod.ONLINE, CREATED, true)).isEqualTo(PAID);
        assertThat(PaymentStatus.of(PaymentMethod.ONLINE, FAILED, true)).isEqualTo(PAID);
    }

    @ParameterizedTest
    @EnumSource(value = ParcelStatus.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void cashAtPickupIsPaidOnceThePaRcelIsPickedUp(ParcelStatus status) {
        assertThat(PaymentStatus.of(PaymentMethod.AT_PICKUP, CREATED, false)).isEqualTo(DUE);
        assertThat(PaymentStatus.of(PaymentMethod.AT_PICKUP, status, false)).isEqualTo(PAID);
    }

    @Test
    void cashOnDeliveryIsPaidWhenDeliveredAndNotChargedWhenTheDeliveryFailed() {
        for (PaymentMethod method : new PaymentMethod[] {PaymentMethod.AT_DROP_OFF, null}) {
            assertThat(PaymentStatus.of(method, CREATED, false)).isEqualTo(DUE);
            assertThat(PaymentStatus.of(method, PICKED_UP, false)).isEqualTo(DUE);
            assertThat(PaymentStatus.of(method, OUT_FOR_DELIVERY, false)).isEqualTo(DUE);
            assertThat(PaymentStatus.of(method, DELIVERED, false)).isEqualTo(PAID);
            assertThat(PaymentStatus.of(method, FAILED, false)).isEqualTo(NOT_CHARGED);
        }
    }

    @Test
    void cashIsCollectedAtPickupOrDropOff() {
        assertThat(PaymentStatus.cashCollectedAt(PaymentMethod.AT_PICKUP)).isEqualTo(PICKED_UP);
        assertThat(PaymentStatus.cashCollectedAt(PaymentMethod.AT_DROP_OFF)).isEqualTo(DELIVERED);
        assertThat(PaymentStatus.cashCollectedAt(null)).isEqualTo(DELIVERED);
        assertThat(PaymentStatus.cashCollectedAt(PaymentMethod.ONLINE)).isNull();
    }
}
