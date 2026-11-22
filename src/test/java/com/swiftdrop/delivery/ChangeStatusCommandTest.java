package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ChangeStatusCommandTest {

    private static final UUID PARCEL_ID = UUID.randomUUID();
    private static final UUID COURIER_ID = UUID.randomUUID();

    @Test
    void optionalFieldsMayBeLeftOut() {
        var command = new ChangeStatusCommand(PARCEL_ID, PICKED_UP, COURIER_ID, "COURIER", null, null, null, null);

        assertThat(command.toStatus()).isEqualTo(PICKED_UP);
        assertThat(command.note()).isNull();
        assertThat(command.failureReason()).isNull();
        assertThat(command.recipientName()).isNull();
        assertThat(command.proofPhotoId()).isNull();
    }

    @Test
    void keepsTheProofOfDelivery() {
        UUID photoId = UUID.randomUUID();

        var command = new ChangeStatusCommand(PARCEL_ID, DELIVERED, COURIER_ID, "COURIER",
                "Left with the security guard", null, "Kamala Silva", photoId);

        assertThat(command.note()).isEqualTo("Left with the security guard");
        assertThat(command.recipientName()).isEqualTo("Kamala Silva");
        assertThat(command.proofPhotoId()).isEqualTo(photoId);
    }

    @Test
    void parcelStatusAndActorAreRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        null, PICKED_UP, COURIER_ID, "COURIER", null, null, null, null))
                .withMessage("parcelId");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, null, COURIER_ID, "COURIER", null, null, null, null))
                .withMessage("toStatus");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, PICKED_UP, null, "COURIER", null, null, null, null))
                .withMessage("actorId");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, PICKED_UP, COURIER_ID, null, null, null, null, null))
                .withMessage("actorRole");
    }

    @Test
    void theShortConstructorLeavesTheBranchOut() {
        var command = new ChangeStatusCommand(PARCEL_ID, PICKED_UP, COURIER_ID, "COURIER", null, null, null, null);

        assertThat(command.branch()).isNull();
    }

    @Test
    void atBranchKeepsTheBranch() {
        var command = new ChangeStatusCommand(PARCEL_ID, AT_BRANCH, COURIER_ID, "COURIER", null, null, null, null,
                District.GAMPAHA);

        assertThat(command.branch()).isEqualTo(District.GAMPAHA);
    }

    @Test
    void atBranchNeedsABranch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, AT_BRANCH, COURIER_ID, "COURIER", null, null, null, null, null))
                .withMessage("branch is required for AT_BRANCH");
        assertThatIllegalArgumentException()
                .as("the short constructor has no branch, so it cannot make an AT_BRANCH change")
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, AT_BRANCH, COURIER_ID, "COURIER", null, null, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = ParcelStatus.class, names = "AT_BRANCH", mode = EnumSource.Mode.EXCLUDE)
    void aBranchIsOnlyForAtBranch(ParcelStatus status) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChangeStatusCommand(
                        PARCEL_ID, status, COURIER_ID, "COURIER", null, null, null, null, District.MATARA))
                .withMessage("branch is only for AT_BRANCH");
    }

    @Test
    void failedWithoutAReasonIsNotRejectedHere() {
        // The courier module checks which fields each status needs (see ChangeStatusCommand), not this record.
        var command = new ChangeStatusCommand(PARCEL_ID, FAILED, COURIER_ID, "COURIER", null, null, null, null);

        assertThat(command.failureReason()).isNull();
    }
}
