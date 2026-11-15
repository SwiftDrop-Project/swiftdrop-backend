package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryResponseTest {

    private static final Instant BOOKED_AT = Instant.parse("2026-11-08T04:30:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-11-09T06:00:00Z");
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    @Test
    void aDeliveredParcelShowsWhoReceivedIt() {
        DeliveryResponse response = DeliveryResponse.from(parcel(DELIVERED, null, null, "Kamala Silva", true));

        assertThat(response.proof()).isEqualTo(new DeliveryResponse.Proof("Kamala Silva", true));
        assertThat(response.currentBranch()).isNull();
        assertThat(response.failureReason()).isNull();
        assertThat(response.updatedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void theBranchAndTheFailureReasonComeOnlyWithTheirStatus() {
        DeliveryResponse atBranch = DeliveryResponse.from(parcel(AT_BRANCH, District.GAMPAHA, null, null, false));
        DeliveryResponse failed = DeliveryResponse.from(
                parcel(FAILED, null, FailureReason.RECIPIENT_UNAVAILABLE, null, false));
        DeliveryResponse booked = DeliveryResponse.from(parcel(CREATED, null, null, null, false));

        assertThat(atBranch.currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(failed.failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(failed.proof()).isNull();
        assertThat(booked.currentBranch()).isNull();
        assertThat(booked.failureReason()).isNull();
        assertThat(booked.proof()).isNull();
    }

    @Test
    void historyEntriesKeepTheBranchAndTheReason() {
        DeliveryHistoryEntryResponse entry = DeliveryHistoryEntryResponse.from(new ParcelStatusEntry(FAILED,
                "No answer, called twice", FailureReason.RECIPIENT_UNAVAILABLE, "COURIER", CHANGED_AT));

        assertThat(entry.status()).isEqualTo(FAILED);
        assertThat(entry.failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(entry.note()).isEqualTo("No answer, called twice");
        assertThat(entry.branch()).isNull();
    }

    @Test
    void personalDataStaysOutOfTheLogs() {
        DeliveryResponse response = DeliveryResponse.from(parcel(DELIVERED, null, null, "Kamala Silva", true));
        DeliveryHistoryEntryResponse entry = DeliveryHistoryEntryResponse.from(new ParcelStatusEntry(DELIVERED,
                "Left with the guard", null, "COURIER", CHANGED_AT));

        assertThat(response.toString()).doesNotContain("Kamala", "Nimal", "+9477", "Galle Road");
        assertThat(response.proof().toString()).isEqualTo("Proof[hasPhoto=true]");
        assertThat(entry.toString()).doesNotContain("guard");
    }

    private static DeliverySnapshot parcel(ParcelStatus status, District branch, FailureReason reason,
                                           String proofName, boolean hasPhoto) {
        return new DeliverySnapshot(UUID.randomUUID(), "SD2611004817", status, UUID.randomUUID(), null, PICKUP,
                RECIPIENT, new BigDecimal("2.50"), null, "Call before coming", null, null, BOOKED_AT, CHANGED_AT,
                proofName, hasPhoto, reason, null, new BigDecimal("450.00"), "LKR", 3L, branch);
    }
}
