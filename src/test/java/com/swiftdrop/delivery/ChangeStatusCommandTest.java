package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ChangeStatusCommandTest {

    private static final UUID PARCEL_ID = UUID.randomUUID();
    private static final UUID COURIER_ID = UUID.randomUUID();

    @Test
    void optionalFieldsMayBeLeftOut() {
        var command = new ChangeStatusCommand(PARCEL_ID, PICKED_UP, COURIER_ID, "COURIER", null, null, null);

        assertThat(command.toStatus()).isEqualTo(PICKED_UP);
        assertThat(command.note()).isNull();
        assertThat(command.failureReason()).isNull();
        assertThat(command.recipientName()).isNull();
    }

    @Test
    void keepsTheProofOfDelivery() {
        var command = new ChangeStatusCommand(PARCEL_ID, DELIVERED, COURIER_ID, "COURIER",
                "Left with the security guard", null, "Kamala Silva");

        assertThat(command.note()).isEqualTo("Left with the security guard");
        assertThat(command.recipientName()).isEqualTo("Kamala Silva");
    }

    @Test
    void parcelStatusAndActorAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(null, PICKED_UP, COURIER_ID, "COURIER", null, null, null))
                .withMessage("parcelId");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(PARCEL_ID, null, COURIER_ID, "COURIER", null, null, null))
                .withMessage("toStatus");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(PARCEL_ID, PICKED_UP, null, "COURIER", null, null, null))
                .withMessage("actorId");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(PARCEL_ID, PICKED_UP, COURIER_ID, null, null, null, null))
                .withMessage("actorRole");
    }

    @Test
    void failedWithoutAReasonIsNotRejectedHere() {
        // A missing reason is the user's mistake, so the service checks it and answers 400, not this record.
        var command = new ChangeStatusCommand(PARCEL_ID, FAILED, COURIER_ID, "COURIER", null, null, null);

        assertThat(command.failureReason()).isNull();
    }
}
