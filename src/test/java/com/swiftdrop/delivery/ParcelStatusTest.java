package com.swiftdrop.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.DELIVERED;
import static com.swiftdrop.delivery.ParcelStatus.FAILED;
import static com.swiftdrop.delivery.ParcelStatus.IN_TRANSIT;
import static com.swiftdrop.delivery.ParcelStatus.OUT_FOR_DELIVERY;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ParcelStatusTest {

    /** The only allowed moves. Every other pair of statuses must be rejected. */
    private static final Set<String> ALLOWED = Set.of(
            "CREATED->PICKED_UP",
            "PICKED_UP->IN_TRANSIT",
            "IN_TRANSIT->OUT_FOR_DELIVERY",
            "OUT_FOR_DELIVERY->DELIVERED",
            "CREATED->FAILED",
            "PICKED_UP->FAILED",
            "IN_TRANSIT->FAILED",
            "OUT_FOR_DELIVERY->FAILED");

    /** All 36 (from, to) pairs. */
    static Stream<Arguments> everyPair() {
        return Arrays.stream(ParcelStatus.values())
                .flatMap(from -> Arrays.stream(ParcelStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyTheAllowedMovesArePermitted(ParcelStatus from, ParcelStatus to) {
        boolean expected = ALLOWED.contains(from + "->" + to);

        assertThat(from.canMoveTo(to)).isEqualTo(expected);
    }

    @Test
    void onlyDeliveredAndFailedAreTerminal() {
        assertThat(DELIVERED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(Stream.of(CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY))
                .noneMatch(ParcelStatus::isTerminal);
    }

    @Test
    void nullTargetIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> CREATED.canMoveTo(null));
    }
}
