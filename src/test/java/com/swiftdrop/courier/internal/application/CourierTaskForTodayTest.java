package com.swiftdrop.courier.internal.application;

import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which of a parcel's two steps today the courier's Today list shows: never dependent on the order they were read. */
class CourierTaskForTodayTest {

    private static final UUID PARCEL = UUID.randomUUID();
    private static final Instant MORNING = Instant.parse("2026-11-21T03:58:17Z");
    private static final Instant AFTERNOON = Instant.parse("2026-11-21T05:01:01Z");

    private static CourierTask step(UUID assignmentId, AssignmentType type, AssignmentStatus taskStatus,
                                    ItemOutcome outcome, Instant assignedAt, Instant completedAt) {
        boolean completed = completedAt != null;
        return new CourierTask(assignmentId, PARCEL, type, taskStatus, outcome, completed,
                type == AssignmentType.DELIVERY ? District.GAMPAHA : null,
                type == AssignmentType.PICKUP ? District.GAMPAHA : null,
                assignedAt, 1, completedAt != null ? completedAt : assignedAt, completedAt, null, null);
    }

    @Test
    void theLatestFinishedStepWinsWhenBothAreFinished() {
        CourierTask handedOver = step(UUID.randomUUID(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                ItemOutcome.HANDED_OVER, MORNING.minusSeconds(60), MORNING);
        CourierTask delivered = step(UUID.randomUUID(), AssignmentType.DELIVERY, AssignmentStatus.COMPLETED,
                ItemOutcome.DELIVERED, MORNING.plusSeconds(60), AFTERNOON);

        assertThat(CourierTask.forToday(handedOver, delivered)).isSameAs(delivered);
        assertThat(CourierTask.forToday(delivered, handedOver)).isSameAs(delivered);
    }

    @Test
    void anOpenStepStillWinsOverAFinishedOne() {
        CourierTask handedOver = step(UUID.randomUUID(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                ItemOutcome.HANDED_OVER, MORNING.minusSeconds(60), MORNING);
        CourierTask toDeliver = step(UUID.randomUUID(), AssignmentType.DELIVERY, AssignmentStatus.ASSIGNED,
                ItemOutcome.PENDING, MORNING.plusSeconds(60), null);

        assertThat(CourierTask.forToday(handedOver, toDeliver)).isSameAs(toDeliver);
        assertThat(CourierTask.forToday(toDeliver, handedOver)).isSameAs(toDeliver);
    }

    @Test
    void equalFinishTimesAreDecidedTheSameWayWhateverTheOrder() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        CourierTask a = step(first, AssignmentType.PICKUP, AssignmentStatus.COMPLETED, ItemOutcome.HANDED_OVER,
                MORNING, AFTERNOON);
        CourierTask b = step(second, AssignmentType.DELIVERY, AssignmentStatus.COMPLETED, ItemOutcome.DELIVERED,
                MORNING, AFTERNOON);

        // Same finish and assignment time: the task id decides, in both orders.
        assertThat(CourierTask.forToday(a, b)).isSameAs(b);
        assertThat(CourierTask.forToday(b, a)).isSameAs(b);

        // Same finish time, later assignment wins.
        CourierTask laterAssigned = step(first, AssignmentType.DELIVERY, AssignmentStatus.COMPLETED,
                ItemOutcome.DELIVERED, MORNING.plusSeconds(1), AFTERNOON);
        assertThat(CourierTask.forToday(laterAssigned, b)).isSameAs(laterAssigned);
        assertThat(CourierTask.forToday(b, laterAssigned)).isSameAs(laterAssigned);
    }
}
