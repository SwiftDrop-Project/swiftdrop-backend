package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CreateDeliveryCommandTest {

    private static final ContactAddress PICKUP =
            new ContactAddress("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final ContactAddress DROPOFF =
            new ContactAddress("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);
    private static final BigDecimal TWO_KG = new BigDecimal("2.00");

    @Test
    void customerBookingHasACustomerAndNoMerchant() {
        UUID customerId = UUID.randomUUID();

        var command = new CreateDeliveryCommand(customerId, null, null, PICKUP, DROPOFF, TWO_KG, "Books");

        assertThat(command.customerId()).isEqualTo(customerId);
        assertThat(command.merchantId()).isNull();
    }

    @Test
    void merchantOrderHasAMerchantAndNoCustomer() {
        UUID merchantId = UUID.randomUUID();

        var command = new CreateDeliveryCommand(null, merchantId, "ORDER-1001", PICKUP, DROPOFF, TWO_KG, null);

        assertThat(command.merchantId()).isEqualTo(merchantId);
        assertThat(command.merchantOrderRef()).isEqualTo("ORDER-1001");
    }

    @Test
    void rejectsADeliveryWithBothACustomerAndAMerchant() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateDeliveryCommand(
                UUID.randomUUID(), UUID.randomUUID(), null, PICKUP, DROPOFF, TWO_KG, null));
    }

    @Test
    void rejectsADeliveryWithNeitherACustomerNorAMerchant() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateDeliveryCommand(
                null, null, null, PICKUP, DROPOFF, TWO_KG, null));
    }

    @Test
    void pickupDropoffAndWeightAreRequired() {
        UUID customerId = UUID.randomUUID();

        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, null, DROPOFF, TWO_KG, null));
        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, PICKUP, null, TWO_KG, null));
        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, PICKUP, DROPOFF, null, null));
    }

    @Test
    void contactAddressNeedsEveryField() {
        assertThatNullPointerException().isThrownBy(
                () -> new ContactAddress("Nimal Perera", null, "12 Galle Road", "Colombo 03", District.COLOMBO));
        assertThatNullPointerException().isThrownBy(
                () -> new ContactAddress("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", null));
    }
}
