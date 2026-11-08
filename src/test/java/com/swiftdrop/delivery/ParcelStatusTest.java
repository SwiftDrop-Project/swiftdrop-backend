package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.IN_TRANSIT;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;

class ParcelStatusTest {

    @Test
    void eachStatusHasTheAgreedNextStatuses() {
        assertThat(CREATED.next()).containsExactly(PICKED_UP);
        assertThat(PICKED_UP.next()).containsExactly(AT_BRANCH);
        assertThat(AT_BRANCH.next()).containsExactlyInAnyOrder(IN_TRANSIT, OUT_FOR_DELIVERY);
        assertThat(IN_TRANSIT.next()).containsExactly(AT_BRANCH);
        assertThat(OUT_FOR_DELIVERY.next()).containsExactlyInAnyOrder(DELIVERED, FAILED);
        assertThat(FAILED.next()).containsExactly(AT_BRANCH);
        assertThat(DELIVERED.next()).isEmpty();
    }

    @Test
    void aParcelCanPassThroughSeveralBranches() {
        // Delgoda → Gampaha branch → Matara branch → Deniyaya, with one failed attempt on the way
        ParcelStatus[] journey = {CREATED, PICKED_UP, AT_BRANCH, IN_TRANSIT, AT_BRANCH, OUT_FOR_DELIVERY, FAILED,
                AT_BRANCH, OUT_FOR_DELIVERY, DELIVERED};
        for (int i = 1; i < journey.length; i++) {
            assertThat(journey[i - 1].canMoveTo(journey[i])).as(journey[i - 1] + " -> " + journey[i]).isTrue();
        }
    }

    @Test
    void stepsCannotBeSkippedOrReversed() {
        assertThat(CREATED.canMoveTo(DELIVERED)).isFalse();
        assertThat(PICKED_UP.canMoveTo(IN_TRANSIT)).as("a picked-up parcel goes to a branch first").isFalse();
        assertThat(PICKED_UP.canMoveTo(OUT_FOR_DELIVERY)).isFalse();
        assertThat(IN_TRANSIT.canMoveTo(OUT_FOR_DELIVERY)).as("a transfer ends at a branch").isFalse();
        assertThat(IN_TRANSIT.canMoveTo(PICKED_UP)).isFalse();
        assertThat(IN_TRANSIT.canMoveTo(FAILED)).isFalse();
        assertThat(AT_BRANCH.canMoveTo(DELIVERED)).isFalse();
        assertThat(FAILED.canMoveTo(OUT_FOR_DELIVERY)).as("a failed parcel goes back to a branch first").isFalse();
        assertThat(DELIVERED.canMoveTo(AT_BRANCH)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ParcelStatus.class)
    void canMoveToMatchesNextAndNeverToItselfOrNull(ParcelStatus status) {
        for (ParcelStatus target : ParcelStatus.values()) {
            assertThat(status.canMoveTo(target)).isEqualTo(status.next().contains(target));
        }
        assertThat(status.canMoveTo(status)).isFalse();
        assertThat(status.canMoveTo(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ParcelStatus.class)
    void onlyDeliveredIsFinishedAndOnlyFinishedGoesNowhere(ParcelStatus status) {
        assertThat(status.isFinished()).isEqualTo(status == DELIVERED);
        assertThat(status.next().isEmpty()).isEqualTo(status.isFinished());
    }

    @Test
    void nextReturnsACopyThatCallersCannotUseToChangeTheRules() {
        CREATED.next().add(DELIVERED);
        assertThat(CREATED.next()).containsExactly(PICKED_UP);
    }
}
