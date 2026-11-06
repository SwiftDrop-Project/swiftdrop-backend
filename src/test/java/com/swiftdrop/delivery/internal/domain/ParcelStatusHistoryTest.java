package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.FailureReason.RECIPIENT_UNAVAILABLE;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;

class ParcelStatusHistoryTest {

    private static final Instant BOOKED = Instant.parse("2026-11-06T04:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-06T06:00:00Z");
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    @Test
    void theFirstEntryOfACustomerBookingIsMadeByTheCustomer() {
        UUID customerId = UUID.randomUUID();
        Parcel parcel = parcel(customerId, null);

        ParcelStatusHistory entry = ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel);

        assertThat(entry.getParcelId()).isEqualTo(parcel.getId());
        assertThat(entry.getFromStatus()).isNull();
        assertThat(entry.getToStatus()).isEqualTo(CREATED);
        assertThat(entry.getActorId()).isEqualTo(customerId);
        assertThat(entry.getActorRole()).isEqualTo("CUSTOMER");
        assertThat(entry.getOccurredAt()).isEqualTo(BOOKED);
    }

    @Test
    void theFirstEntryOfAMerchantOrderIsMadeByTheMerchant() {
        UUID merchantId = UUID.randomUUID();

        ParcelStatusHistory entry = ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel(null, merchantId));

        assertThat(entry.getActorId()).isEqualTo(merchantId);
        assertThat(entry.getActorRole()).isEqualTo("MERCHANT");
    }

    @Test
    void aChangeCopiesTheCommand() {
        UUID parcelId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        var command = new ChangeStatusCommand(parcelId, FAILED, courierId, "COURIER", "Called twice",
                RECIPIENT_UNAVAILABLE, null, null);

        ParcelStatusHistory entry = ParcelStatusHistory.forChange(UUID.randomUUID(), OUT_FOR_DELIVERY, command, LATER);

        assertThat(entry.getParcelId()).isEqualTo(parcelId);
        assertThat(entry.getFromStatus()).isEqualTo(OUT_FOR_DELIVERY);
        assertThat(entry.getToStatus()).isEqualTo(FAILED);
        assertThat(entry.getActorId()).isEqualTo(courierId);
        assertThat(entry.getActorRole()).isEqualTo("COURIER");
        assertThat(entry.getNote()).isEqualTo("Called twice");
        assertThat(entry.getFailureReason()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(entry.getOccurredAt()).isEqualTo(LATER);
    }

    @Test
    void aFailureReasonIsKeptOnlyForFailed() {
        var command = new ChangeStatusCommand(UUID.randomUUID(), DELIVERED, UUID.randomUUID(), "COURIER", null,
                RECIPIENT_UNAVAILABLE, "Kamala Silva", null);

        ParcelStatusHistory entry = ParcelStatusHistory.forChange(UUID.randomUUID(), OUT_FOR_DELIVERY, command, LATER);

        assertThat(entry.getFailureReason()).isNull();
    }

    @Test
    void thePublicEntryShowsTheNewStatus() {
        var command = new ChangeStatusCommand(UUID.randomUUID(), FAILED, UUID.randomUUID(), "COURIER", "Called twice",
                RECIPIENT_UNAVAILABLE, null, null);

        ParcelStatusEntry entry = ParcelStatusHistory.forChange(UUID.randomUUID(), OUT_FOR_DELIVERY, command, LATER)
                .toEntry();

        assertThat(entry.status()).isEqualTo(FAILED);
        assertThat(entry.note()).isEqualTo("Called twice");
        assertThat(entry.failureReason()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(entry.actorRole()).isEqualTo("COURIER");
        assertThat(entry.occurredAt()).isEqualTo(LATER);
    }

    @Test
    void anEntryIsNewUntilItIsSaved() {
        ParcelStatusHistory entry = ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel(UUID.randomUUID(), null));

        assertThat(entry.isNew()).isTrue();
        entry.markNotNew();
        assertThat(entry.isNew()).isFalse();
    }

    private static Parcel parcel(UUID customerId, UUID merchantId) {
        var command = new CreateDeliveryCommand(customerId, merchantId, null, PICKUP, RECIPIENT,
                new BigDecimal("2.00"), null, null);
        return Parcel.create(UUID.randomUUID(), "SD2611000001", command, new BigDecimal("450.00"), "LKR", BOOKED);
    }
}
