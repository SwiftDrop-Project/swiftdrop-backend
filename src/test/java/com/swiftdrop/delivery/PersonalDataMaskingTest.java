package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.FailureReason.RECIPIENT_UNAVAILABLE;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;

/** Printing a delivery record (for example in a log line) must never show names, phone numbers or street addresses. */
class PersonalDataMaskingTest {

    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);
    private static final String INSTRUCTIONS = "Call +94775550123 at the gate";

    @Test
    void deliveryContactShowsOnlyCityAndDistrict() {
        assertThat(PICKUP.toString()).isEqualTo("DeliveryContact[city=Colombo 03, district=COLOMBO]");
    }

    @Test
    void snapshotHidesTheContactsTheProofNameAndTheInstructions() {
        var snapshot = new DeliverySnapshot(UUID.randomUUID(), "SD2611004817", DELIVERED, UUID.randomUUID(), null,
                PICKUP, RECIPIENT, new BigDecimal("2.00"), null, INSTRUCTIONS, UUID.randomUUID(), Instant.now(),
                Instant.now(), Instant.now(), "Sunil Fernando", true, null, null, new BigDecimal("450.00"), "LKR", 4);

        assertThat(snapshot.toString())
                .contains("SD2611004817", "DELIVERED", "Kandy", "version=4")
                .doesNotContain("Nimal Perera", "+94771234567", "12 Galle Road",
                        "Kamala Silva", "+94719876543", "45 Peradeniya Road", "Sunil Fernando", "+94775550123");
    }

    @Test
    void createCommandHidesTheContactsAndTheInstructions() {
        var command = new CreateDeliveryCommand(UUID.randomUUID(), null, null, PICKUP, RECIPIENT,
                new BigDecimal("2.00"), null, INSTRUCTIONS);

        assertThat(command.toString())
                .contains("Colombo 03", "Kandy")
                .doesNotContain("Nimal Perera", "+94771234567", "12 Galle Road",
                        "Kamala Silva", "+94719876543", "45 Peradeniya Road", "+94775550123");
    }

    @Test
    void historyEntryShowsTheFailureCodeButHidesTheNote() {
        var entry = new ParcelStatusEntry(FAILED, "Kamala Silva was not at home", RECIPIENT_UNAVAILABLE, "COURIER",
                Instant.now());

        assertThat(entry.toString())
                .contains("FAILED", "RECIPIENT_UNAVAILABLE", "COURIER")
                .doesNotContain("Kamala Silva");
    }

    @Test
    void outboxMessageHidesThePayload() {
        var message = new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), "parcel.status.changed",
                "{\"note\":\"Call Kamala on +94719876543\"}", Instant.now());

        assertThat(message.toString())
                .contains("parcel.status.changed")
                .doesNotContain("Kamala", "+94719876543");
    }

    @Test
    void statusEventShowsTheFailureCodeButHidesTheNote() {
        var event = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 3, "SD2611004817",
                UUID.randomUUID(), null, OUT_FOR_DELIVERY, FAILED, UUID.randomUUID(), "COURIER",
                "Kamala Silva did not answer +94719876543", RECIPIENT_UNAVAILABLE.name(), Instant.now());

        assertThat(event.toString())
                .contains("SD2611004817", "OUT_FOR_DELIVERY -> FAILED", "parcelVersion=3",
                        "failureReason=RECIPIENT_UNAVAILABLE")
                .doesNotContain("Kamala Silva", "+94719876543");
    }
}
