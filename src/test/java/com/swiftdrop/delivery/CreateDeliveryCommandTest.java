package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CreateDeliveryCommandTest {

    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);
    private static final BigDecimal TWO_KG = new BigDecimal("2.00");

    @Test
    void customerBookingHasACustomerAndNoMerchant() {
        UUID customerId = UUID.randomUUID();

        var command = new CreateDeliveryCommand(customerId, null, null, PICKUP, RECIPIENT, TWO_KG, null,
                "Call before coming");

        assertThat(command.customerId()).isEqualTo(customerId);
        assertThat(command.merchantId()).isNull();
        assertThat(command.instructions()).isEqualTo("Call before coming");
    }

    @Test
    void merchantOrderHasAMerchantAndNoCustomer() {
        UUID merchantId = UUID.randomUUID();
        BigDecimal cashToCollect = new BigDecimal("2500.00");

        var command = new CreateDeliveryCommand(null, merchantId, "ORDER-1001", PICKUP, RECIPIENT, TWO_KG,
                cashToCollect, null);

        assertThat(command.merchantId()).isEqualTo(merchantId);
        assertThat(command.merchantOrderRef()).isEqualTo("ORDER-1001");
        assertThat(command.codAmount()).isEqualTo(cashToCollect);
    }

    @Test
    void rejectsADeliveryWithBothACustomerAndAMerchant() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateDeliveryCommand(
                UUID.randomUUID(), UUID.randomUUID(), null, PICKUP, RECIPIENT, TWO_KG, null, null));
    }

    @Test
    void rejectsADeliveryWithNeitherACustomerNorAMerchant() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateDeliveryCommand(
                null, null, null, PICKUP, RECIPIENT, TWO_KG, null, null));
    }

    @Test
    void pickupRecipientAndWeightAreRequired() {
        UUID customerId = UUID.randomUUID();

        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, null, RECIPIENT, TWO_KG, null, null)).withMessage("pickup");
        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, PICKUP, null, TWO_KG, null, null)).withMessage("recipient");
        assertThatNullPointerException().isThrownBy(() -> new CreateDeliveryCommand(
                customerId, null, null, PICKUP, RECIPIENT, null, null, null)).withMessage("weightKg");
    }

    @Test
    void deliveryContactNeedsEveryField() {
        assertThatNullPointerException().isThrownBy(
                () -> new DeliveryContact("Nimal Perera", null, "12 Galle Road", "Colombo 03", District.COLOMBO))
                .withMessage("phone");
        assertThatNullPointerException().isThrownBy(
                () -> new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", null))
                .withMessage("district");
    }
}
