package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.FailureReason.RECIPIENT_UNAVAILABLE;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.IN_TRANSIT;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParcelTest {

    private static final Instant BOOKED = Instant.parse("2026-11-06T04:00:00Z");
    private static final Instant ASSIGNED = Instant.parse("2026-11-06T05:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-06T06:00:00Z");

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID COURIER_ID = UUID.randomUUID();
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    @Test
    void aNewParcelStartsAsCreated() {
        Parcel parcel = customerParcel();

        assertThat(parcel.getStatus()).isEqualTo(CREATED);
        assertThat(parcel.getCreatedAt()).isEqualTo(BOOKED);
        assertThat(parcel.getUpdatedAt()).isEqualTo(BOOKED);
        assertThat(parcel.getStatusChangedAt()).isEqualTo(BOOKED);
        assertThat(parcel.getAssignedCourierId()).isNull();
        assertThat(parcel.getVersion()).isNull();
    }

    @Test
    void aCourierCanBeAssignedAndReplacedWhileCreated() {
        Parcel parcel = customerParcel();
        UUID otherCourier = UUID.randomUUID();

        parcel.assignCourier(COURIER_ID, ASSIGNED);
        parcel.assignCourier(otherCourier, LATER);

        assertThat(parcel.getAssignedCourierId()).isEqualTo(otherCourier);
        assertThat(parcel.getAssignedAt()).isEqualTo(LATER);
        assertThat(parcel.getUpdatedAt()).isEqualTo(LATER);
        // assigning a courier is not a status change
        assertThat(parcel.getStatus()).isEqualTo(CREATED);
        assertThat(parcel.getStatusChangedAt()).isEqualTo(BOOKED);
    }

    @Test
    void aCourierCannotBeAssignedAfterPickUp() {
        Parcel parcel = customerParcel();
        parcel.changeStatus(move(parcel, PICKED_UP), LATER);

        assertThatThrownBy(() -> parcel.assignCourier(COURIER_ID, LATER))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PARCEL_NOT_ASSIGNABLE));
    }

    @Test
    void assigningNeedsACourier() {
        Parcel parcel = customerParcel();

        assertThatNullPointerException().isThrownBy(() -> parcel.assignCourier(null, LATER))
                .withMessage("courierId");
    }

    @Test
    void theStatusMovesOneStepAndReturnsTheOldOne() {
        Parcel parcel = customerParcel();

        ParcelStatus from = parcel.changeStatus(move(parcel, PICKED_UP), LATER);

        assertThat(from).isEqualTo(CREATED);
        assertThat(parcel.getStatus()).isEqualTo(PICKED_UP);
        assertThat(parcel.getStatusChangedAt()).isEqualTo(LATER);
        assertThat(parcel.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void aStepCannotBeSkippedAndNothingChanges() {
        Parcel parcel = customerParcel();

        assertThatThrownBy(() -> parcel.changeStatus(move(parcel, DELIVERED), LATER))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        assertThat(parcel.getStatus()).isEqualTo(CREATED);
        assertThat(parcel.getStatusChangedAt()).isEqualTo(BOOKED);
    }

    @Test
    void failingKeepsTheReason() {
        Parcel parcel = outForDelivery();

        parcel.changeStatus(new ChangeStatusCommand(parcel.getId(), FAILED, COURIER_ID, "COURIER",
                "Called twice", RECIPIENT_UNAVAILABLE, null, null), LATER);

        assertThat(parcel.getStatus()).isEqualTo(FAILED);
        assertThat(parcel.getFailureReason()).isEqualTo(RECIPIENT_UNAVAILABLE);
        assertThat(parcel.getProofRecipientName()).isNull();
    }

    @Test
    void deliveringKeepsTheProof() {
        Parcel parcel = outForDelivery();
        UUID photoId = UUID.randomUUID();

        parcel.changeStatus(new ChangeStatusCommand(parcel.getId(), DELIVERED, COURIER_ID, "COURIER",
                null, null, "Kamala Silva", photoId), LATER);

        assertThat(parcel.getProofRecipientName()).isEqualTo("Kamala Silva");
        assertThat(parcel.getProofPhotoId()).isEqualTo(photoId);
        assertThat(parcel.getFailureReason()).isNull();
        assertThat(parcel.toSnapshot().hasProofPhoto()).isTrue();
    }

    @Test
    void theSnapshotCopiesEveryField() {
        // different values everywhere, so two swapped fields of the same type would be caught
        UUID merchantId = UUID.randomUUID();
        var command = new CreateDeliveryCommand(null, merchantId, "ORDER-1001", PICKUP, RECIPIENT,
                new BigDecimal("1.50"), new BigDecimal("2500.00"), "Leave at the gate");
        Parcel parcel = Parcel.create(UUID.randomUUID(), "SD2611004817", command, new BigDecimal("380.00"), "LKR",
                BOOKED);
        parcel.assignCourier(COURIER_ID, ASSIGNED);
        parcel.changeStatus(move(parcel, PICKED_UP), LATER);

        DeliverySnapshot snapshot = parcel.toSnapshot();

        assertThat(snapshot.id()).isEqualTo(parcel.getId());
        assertThat(snapshot.trackingNumber()).isEqualTo("SD2611004817");
        assertThat(snapshot.status()).isEqualTo(PICKED_UP);
        assertThat(snapshot.customerId()).isNull();
        assertThat(snapshot.merchantId()).isEqualTo(merchantId);
        assertThat(snapshot.pickup()).isEqualTo(PICKUP);
        assertThat(snapshot.recipient()).isEqualTo(RECIPIENT);
        assertThat(snapshot.weightKg()).isEqualByComparingTo("1.50");
        assertThat(snapshot.codAmount()).isEqualByComparingTo("2500.00");
        assertThat(snapshot.instructions()).isEqualTo("Leave at the gate");
        assertThat(snapshot.assignedCourierId()).isEqualTo(COURIER_ID);
        assertThat(snapshot.assignedAt()).isEqualTo(ASSIGNED);
        assertThat(snapshot.createdAt()).isEqualTo(BOOKED);
        assertThat(snapshot.updatedAt()).isEqualTo(LATER);
        assertThat(snapshot.proofRecipientName()).isNull();
        assertThat(snapshot.hasProofPhoto()).isFalse();
        assertThat(snapshot.failureReason()).isNull();
        assertThat(snapshot.merchantOrderRef()).isEqualTo("ORDER-1001");
        assertThat(snapshot.feeAmount()).isEqualByComparingTo("380.00");
        assertThat(snapshot.currency()).isEqualTo("LKR");
        assertThat(snapshot.version()).isZero();
    }

    private static Parcel customerParcel() {
        var command = new CreateDeliveryCommand(CUSTOMER_ID, null, null, PICKUP, RECIPIENT,
                new BigDecimal("2.00"), null, null);
        return Parcel.create(UUID.randomUUID(), "SD2611000001", command, new BigDecimal("450.00"), "LKR", BOOKED);
    }

    private static Parcel outForDelivery() {
        Parcel parcel = customerParcel();
        parcel.changeStatus(move(parcel, PICKED_UP), LATER);
        parcel.changeStatus(move(parcel, IN_TRANSIT), LATER);
        parcel.changeStatus(move(parcel, OUT_FOR_DELIVERY), LATER);
        return parcel;
    }

    private static ChangeStatusCommand move(Parcel parcel, ParcelStatus to) {
        return new ChangeStatusCommand(parcel.getId(), to, COURIER_ID, "COURIER", null, null, null, null);
    }
}
