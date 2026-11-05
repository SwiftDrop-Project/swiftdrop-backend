package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        assertThat(PICKED_UP.next()).containsExactly(IN_TRANSIT);
        assertThat(IN_TRANSIT.next()).containsExactly(OUT_FOR_DELIVERY);
        assertThat(OUT_FOR_DELIVERY.next()).containsExactlyInAnyOrder(DELIVERED, FAILED);
        assertThat(DELIVERED.next()).isEmpty();
        assertThat(FAILED.next()).isEmpty();
    }

    @Test
    void stepsCannotBeSkippedOrReversed() {
        assertThat(CREATED.canMoveTo(DELIVERED)).isFalse();
        assertThat(PICKED_UP.canMoveTo(OUT_FOR_DELIVERY)).isFalse();
        assertThat(IN_TRANSIT.canMoveTo(PICKED_UP)).isFalse();
        assertThat(IN_TRANSIT.canMoveTo(FAILED)).isFalse();
        assertThat(FAILED.canMoveTo(OUT_FOR_DELIVERY)).isFalse();
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
    void onlyDeliveredAndFailedAreFinishedAndFinishedStatusesGoNowhere(ParcelStatus status) {
        assertThat(status.isFinished()).isEqualTo(status == DELIVERED || status == FAILED);
        assertThat(status.next().isEmpty()).isEqualTo(status.isFinished());
    }

    @Test
    void nextReturnsACopyThatCallersCannotUseToChangeTheRules() {
        CREATED.next().add(DELIVERED);
        assertThat(CREATED.next()).containsExactly(PICKED_UP);
    }
}
