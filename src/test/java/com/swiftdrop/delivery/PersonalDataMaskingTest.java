package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;

/** Printing a delivery record (for example in a log line) must never show names, phone numbers or street addresses. */
class PersonalDataMaskingTest {

    private static final ContactAddress PICKUP =
            new ContactAddress("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final ContactAddress DROPOFF =
            new ContactAddress("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    @Test
    void contactAddressShowsOnlyCityAndDistrict() {
        assertThat(PICKUP.toString()).isEqualTo("ContactAddress[city=Colombo 03, district=COLOMBO]");
    }

    @Test
    void snapshotHidesThePickupAndDropoffContacts() {
        var snapshot = new DeliverySnapshot(UUID.randomUUID(), "SD-7K3M-Q9TX", UUID.randomUUID(), null, null, null,
                CREATED, PICKUP, DROPOFF, new BigDecimal("2.00"), "Books", new BigDecimal("450.00"), "LKR", 0,
                Instant.now(), Instant.now());

        assertThat(snapshot.toString())
                .contains("SD-7K3M-Q9TX", "Kandy")
                .doesNotContain("Nimal Perera", "+94771234567", "12 Galle Road",
                        "Kamala Silva", "+94719876543", "45 Peradeniya Road");
    }

    @Test
    void createCommandHidesTheContactsToo() {
        var command = new CreateDeliveryCommand(UUID.randomUUID(), null, null, PICKUP, DROPOFF,
                new BigDecimal("2.00"), null);

        assertThat(command.toString()).doesNotContain("Nimal Perera", "+94771234567", "12 Galle Road");
    }

    @Test
    void historyEntryHidesTheRecipientAndFreeText() {
        var entry = new StatusHistoryEntry(OUT_FOR_DELIVERY, DELIVERED, UUID.randomUUID(), "COURIER",
                "Left with the security guard", null, "Kamala Silva", Instant.now());

        assertThat(entry.toString())
                .contains("OUT_FOR_DELIVERY -> DELIVERED", "COURIER")
                .doesNotContain("Kamala Silva", "security guard");
    }
}
