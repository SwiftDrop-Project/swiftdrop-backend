package com.swiftdrop.courier.internal.application;

import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CourierTaskTest {

    private static final UUID COURIER_ID = UUID.randomUUID();
    private static final Instant UPDATED_AT = Instant.parse("2026-11-18T05:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2026-11-18T05:30:00Z");

    @Test
    void cancellationOfAnUntouchedItemDoesNotMeanTheCourierCompletedIt() {
        CourierTask courierTask = courierTask(AssignmentType.PICKUP, AssignmentStatus.CANCELLED,
                ItemOutcome.PENDING, CLOSED_AT);

        assertThat(courierTask.completed()).isFalse();
        assertThat(courierTask.completedAt()).isNull();
        assertThat(courierTask.closed()).isTrue();
    }

    @Test
    void completionUsesOnlyTheOutcomesThatCloseCourierWorkForEachTaskType() {
        assertCompleted(AssignmentType.PICKUP, ItemOutcome.HANDED_OVER);
        assertCompleted(AssignmentType.PICKUP, ItemOutcome.NOT_COLLECTED);
        assertCompleted(AssignmentType.TRANSFER, ItemOutcome.HANDED_OVER);
        assertCompleted(AssignmentType.TRANSFER, ItemOutcome.NOT_LOADED);
        assertCompleted(AssignmentType.DELIVERY, ItemOutcome.DELIVERED);
        assertCompleted(AssignmentType.DELIVERY, ItemOutcome.RETURNED);
        assertCompleted(AssignmentType.DELIVERY, ItemOutcome.NOT_LOADED);

        assertNotCompleted(AssignmentType.PICKUP, ItemOutcome.COLLECTED);
        assertNotCompleted(AssignmentType.TRANSFER, ItemOutcome.LOADED);
        assertNotCompleted(AssignmentType.DELIVERY, ItemOutcome.FAILED);
    }

    @Test
    void completedOutcomeRemainsCompletedIfParentSnapshotIsCancelled() {
        UUID assignmentId = UUID.randomUUID();
        AssignmentItemView completedItem = item(assignmentId, ItemOutcome.HANDED_OVER, CLOSED_AT);
        AssignmentItemView untouchedItem = item(assignmentId, ItemOutcome.PENDING, CLOSED_AT);
        AssignmentView cancelledTask = task(assignmentId, AssignmentType.PICKUP, AssignmentStatus.CANCELLED,
                List.of(completedItem, untouchedItem));

        CourierTask completed = CourierTask.of(cancelledTask, completedItem);
        CourierTask cancelled = CourierTask.of(cancelledTask, untouchedItem);

        assertThat(completed.completed()).isTrue();
        assertThat(completed.completedAt()).isEqualTo(CLOSED_AT);
        assertThat(cancelled.completed()).isFalse();
        assertThat(cancelled.completedAt()).isNull();
    }

    private static void assertCompleted(AssignmentType type, ItemOutcome outcome) {
        CourierTask task = courierTask(type, AssignmentStatus.COMPLETED, outcome, CLOSED_AT);
        assertThat(task.completed()).as("%s in %s", outcome, type).isTrue();
        assertThat(task.completedAt()).isEqualTo(CLOSED_AT);
    }

    private static void assertNotCompleted(AssignmentType type, ItemOutcome outcome) {
        CourierTask task = courierTask(type, AssignmentStatus.IN_PROGRESS, outcome, null);
        assertThat(task.completed()).as("%s in %s", outcome, type).isFalse();
        assertThat(task.completedAt()).isNull();
    }

    private static CourierTask courierTask(
            AssignmentType type,
            AssignmentStatus status,
            ItemOutcome outcome,
            Instant closedAt) {
        UUID assignmentId = UUID.randomUUID();
        AssignmentItemView item = item(assignmentId, outcome, closedAt);
        return CourierTask.of(task(assignmentId, type, status, List.of(item)), item);
    }

    private static AssignmentView task(
            UUID assignmentId,
            AssignmentType type,
            AssignmentStatus status,
            List<AssignmentItemView> items) {
        return new AssignmentView(assignmentId, COURIER_ID, type, status,
                type == AssignmentType.PICKUP ? null : District.COLOMBO,
                type == AssignmentType.DELIVERY ? null : District.KANDY,
                LocalDate.of(2026, 11, 18), null, UPDATED_AT, UPDATED_AT, null, null, items, 1);
    }

    private static AssignmentItemView item(UUID assignmentId, ItemOutcome outcome, Instant closedAt) {
        return new AssignmentItemView(assignmentId, UUID.randomUUID(), 1, outcome, null, null, null, null, null,
                UPDATED_AT, closedAt);
    }
}
