package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordOnlinePaymentCommandTest {

    @Test
    void acceptsAPaymentsReference() {
        RecordOnlinePaymentCommand command = new RecordOnlinePaymentCommand(UUID.randomUUID(),
                "payments:3f1d2c4b-0b6c-4a51-9d2e-3a4f5b6c7d8e");
        assertThat(command.externalReference()).startsWith("payments:");
        assertThat(new RecordOnlinePaymentCommand(UUID.randomUUID(), "x".repeat(100)).externalReference()).hasSize(100);
    }

    @Test
    void refusesAMissingParcelOrAnInvalidReference() {
        assertThatThrownBy(() -> new RecordOnlinePaymentCommand(null, "payments:1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecordOnlinePaymentCommand(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        for (String bad : new String[] {"", " ", ":starts-with-colon", "has space", "x".repeat(101),
                "4242 4242 4242 4242", "card=4242424242424242;cvc=123"}) {
            assertThatThrownBy(() -> new RecordOnlinePaymentCommand(UUID.randomUUID(), bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
