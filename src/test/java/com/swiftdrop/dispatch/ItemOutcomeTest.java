package com.swiftdrop.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static com.swiftdrop.dispatch.AssignmentType.DELIVERY;
import static com.swiftdrop.dispatch.AssignmentType.PICKUP;
import static com.swiftdrop.dispatch.AssignmentType.TRANSFER;
import static com.swiftdrop.dispatch.ItemOutcome.COLLECTED;
import static com.swiftdrop.dispatch.ItemOutcome.DELIVERED;
import static com.swiftdrop.dispatch.ItemOutcome.FAILED;
import static com.swiftdrop.dispatch.ItemOutcome.HANDED_OVER;
import static com.swiftdrop.dispatch.ItemOutcome.LOADED;
import static com.swiftdrop.dispatch.ItemOutcome.NOT_COLLECTED;
import static com.swiftdrop.dispatch.ItemOutcome.NOT_LOADED;
import static com.swiftdrop.dispatch.ItemOutcome.PENDING;
import static com.swiftdrop.dispatch.ItemOutcome.RETURNED;
import static org.assertj.core.api.Assertions.assertThat;

class ItemOutcomeTest {

    @Test
    void pickupCollectsThenHandsOverAtTheBranch() {
        assertThat(PENDING.next(PICKUP)).containsExactlyInAnyOrder(COLLECTED, NOT_COLLECTED);
        assertThat(COLLECTED.next(PICKUP)).containsExactly(HANDED_OVER);
        assertThat(HANDED_OVER.next(PICKUP)).isEmpty();
        assertThat(NOT_COLLECTED.next(PICKUP)).isEmpty();
    }

    @Test
    void transferLoadsThenHandsOverAtTheBranch() {
        assertThat(PENDING.next(TRANSFER)).containsExactlyInAnyOrder(LOADED, NOT_LOADED);
        assertThat(LOADED.next(TRANSFER)).containsExactly(HANDED_OVER);
        assertThat(HANDED_OVER.next(TRANSFER)).isEmpty();
        assertThat(NOT_LOADED.next(TRANSFER)).isEmpty();
    }

    @Test
    void deliveryLoadsThenDeliversOrFailsAndReturnsFailedParcels() {
        assertThat(PENDING.next(DELIVERY)).containsExactlyInAnyOrder(LOADED, NOT_LOADED);
        assertThat(LOADED.next(DELIVERY)).containsExactlyInAnyOrder(DELIVERED, FAILED);
        assertThat(FAILED.next(DELIVERY)).containsExactly(RETURNED);
        assertThat(DELIVERED.next(DELIVERY)).isEmpty();
        assertThat(RETURNED.next(DELIVERY)).isEmpty();
        assertThat(NOT_LOADED.next(DELIVERY)).isEmpty();
    }

    @Test
    void outcomesOfAnotherTypeLeadNowhere() {
        assertThat(LOADED.next(PICKUP)).isEmpty();
        assertThat(FAILED.next(PICKUP)).isEmpty();
        assertThat(COLLECTED.next(TRANSFER)).isEmpty();
        assertThat(COLLECTED.next(DELIVERY)).isEmpty();
        assertThat(PENDING.canMoveTo(PICKUP, LOADED)).isFalse();
        assertThat(PENDING.canMoveTo(TRANSFER, COLLECTED)).isFalse();
        assertThat(LOADED.canMoveTo(TRANSFER, DELIVERED)).as("a transfer ends at a branch").isFalse();
        assertThat(LOADED.canMoveTo(DELIVERY, HANDED_OVER)).as("a delivery ends at the recipient").isFalse();
    }

    @Test
    void stepsCannotBeSkipped() {
        assertThat(PENDING.canMoveTo(PICKUP, HANDED_OVER)).as("collect before handing over").isFalse();
        assertThat(PENDING.canMoveTo(DELIVERY, DELIVERED)).as("load before delivering").isFalse();
        assertThat(LOADED.canMoveTo(DELIVERY, RETURNED)).as("only failed parcels are returned").isFalse();
        assertThat(PENDING.canMoveTo(DELIVERY, PENDING)).isFalse();
    }

    @Test
    void closingOutcomesPerType() {
        assertThat(closing(PICKUP)).containsExactlyInAnyOrder(HANDED_OVER, NOT_COLLECTED);
        assertThat(closing(TRANSFER)).containsExactlyInAnyOrder(HANDED_OVER, NOT_LOADED);
        assertThat(closing(DELIVERY)).containsExactlyInAnyOrder(DELIVERED, RETURNED, NOT_LOADED);
    }

    @Test
    void aFailedDeliveryStaysOpenUntilTheParcelIsReturned() {
        assertThat(FAILED.closes(DELIVERY)).isFalse();
        assertThat(RETURNED.closes(DELIVERY)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AssignmentType.class)
    void anOutcomeClosesExactlyWhenItIsReachableAndLeadsNowhere(AssignmentType type) {
        Set<ItemOutcome> reachable = reachableFromPending(type);
        for (ItemOutcome outcome : ItemOutcome.values()) {
            boolean expected = reachable.contains(outcome) && outcome.next(type).isEmpty();
            assertThat(outcome.closes(type)).as(type + " " + outcome).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(AssignmentType.class)
    void canMoveToMatchesNextAndNeverToItselfOrNull(AssignmentType type) {
        for (ItemOutcome outcome : ItemOutcome.values()) {
            for (ItemOutcome target : ItemOutcome.values()) {
                assertThat(outcome.canMoveTo(type, target)).isEqualTo(outcome.next(type).contains(target));
            }
            assertThat(outcome.canMoveTo(type, outcome)).isFalse();
            assertThat(outcome.canMoveTo(type, null)).isFalse();
            assertThat(outcome.next(type)).doesNotContain(PENDING);
        }
    }

    @Test
    void nextReturnsACopyThatCallersCannotUseToChangeTheRules() {
        PENDING.next(PICKUP).add(DELIVERED);
        assertThat(PENDING.next(PICKUP)).containsExactlyInAnyOrder(COLLECTED, NOT_COLLECTED);
    }

    private static Set<ItemOutcome> closing(AssignmentType type) {
        Set<ItemOutcome> result = EnumSet.noneOf(ItemOutcome.class);
        for (ItemOutcome outcome : ItemOutcome.values()) {
            if (outcome.closes(type)) {
                result.add(outcome);
            }
        }
        return result;
    }

    private static Set<ItemOutcome> reachableFromPending(AssignmentType type) {
        Set<ItemOutcome> reached = EnumSet.noneOf(ItemOutcome.class);
        Set<ItemOutcome> frontier = PENDING.next(type);
        while (!frontier.isEmpty()) {
            Set<ItemOutcome> following = EnumSet.noneOf(ItemOutcome.class);
            for (ItemOutcome outcome : frontier) {
                if (reached.add(outcome)) {
                    following.addAll(outcome.next(type));
                }
            }
            frontier = following;
        }
        return reached;
    }
}
