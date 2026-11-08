// TEMPORARY - delete when WP8 implements AssignmentApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeAssignmentApiTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z"); // 11:30 in Colombo
    private static final Instant START_OF_TODAY = Instant.parse("2026-11-05T18:30:00Z");

    private final UUID courier = UUID.randomUUID();
    private final UUID otherCourier = UUID.randomUUID();
    private FakeAssignmentApi fake;
    private FakeDeliveryApi deliveries;

    @BeforeEach
    void seed() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        fake = new FakeAssignmentApi(clock);
        deliveries = new FakeDeliveryApi(clock);
        FakeDeliverySeeder.seed(deliveries, fake, courier, otherCourier, NOW);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theDemoCourierHasOneAssignedTaskOfEachTypeForToday() {
        List<AssignmentView> today = fake.findOpenForCourier(courier, START_OF_TODAY);

        assertThat(today).extracting(AssignmentView::type).containsExactlyInAnyOrder(
                AssignmentType.PICKUP, AssignmentType.TRANSFER, AssignmentType.DELIVERY);
        assertThat(today).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(AssignmentStatus.ASSIGNED);
            assertThat(task.scheduledFor()).isEqualTo(LocalDate.of(2026, 11, 6));
            assertThat(task.items()).allMatch(item -> item.outcome() == ItemOutcome.PENDING);
        });
        assertThat(task(AssignmentType.PICKUP).destinationBranch()).isEqualTo(District.GAMPAHA);
        assertThat(task(AssignmentType.PICKUP).items()).hasSize(3);
        assertThat(task(AssignmentType.TRANSFER).originBranch()).isEqualTo(District.GAMPAHA);
        assertThat(task(AssignmentType.TRANSFER).destinationBranch()).isEqualTo(District.MATARA);
        assertThat(task(AssignmentType.DELIVERY).originBranch()).isEqualTo(District.MATARA);
        assertThat(task(AssignmentType.DELIVERY).items()).extracting(AssignmentItemView::sequence)
                .containsExactly(1, 2, 3);

        List<AssignmentView> others = fake.findOpenForCourier(otherCourier, START_OF_TODAY);
        assertThat(others).singleElement().satisfies(task -> {
            assertThat(task.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
            assertThat(task.items().getFirst().outcome()).isEqualTo(ItemOutcome.LOADED);
        });
    }

    @Test
    void everyReadIsScopedToTheCourier() {
        AssignmentView pickup = task(AssignmentType.PICKUP);
        UUID parcel = pickup.items().getFirst().parcelId();

        assertThat(fake.findByIdForCourier(pickup.id(), courier)).contains(pickup);
        assertThat(fake.findByIdForCourier(pickup.id(), otherCourier)).isEmpty();
        assertThat(fake.findByIdForCourier(UUID.randomUUID(), courier)).isEmpty();
        assertThat(fake.findItemsForCourierAndParcel(courier, parcel)).singleElement()
                .extracting(AssignmentItemView::assignmentId).isEqualTo(pickup.id());
        assertThat(fake.findItemsForCourierAndParcel(otherCourier, parcel)).isEmpty();
    }

    @Test
    void theFirstOutcomeStartsTheTaskAndAClosingOutcomeClosesTheItem() {
        AssignmentView pickup = task(AssignmentType.PICKUP);
        UUID first = pickup.items().get(0).parcelId();
        UUID second = pickup.items().get(1).parcelId();

        AssignmentView started = fake.recordOutcome(command(pickup, first, ItemOutcome.COLLECTED));
        assertThat(started.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        assertThat(started.startedAt()).isEqualTo(NOW);
        assertThat(started.version()).isEqualTo(pickup.version() + 1);
        assertThat(item(started, first).closedAt()).isNull();

        AssignmentView notCollected = fake.recordOutcome(new RecordOutcomeCommand(pickup.id(), second, courier,
                ItemOutcome.NOT_COLLECTED, TaskProblem.SENDER_UNAVAILABLE, null, "Closed", null, null));
        assertThat(item(notCollected, second).closedAt()).isEqualTo(NOW);
        assertThat(item(notCollected, second).problem()).isEqualTo(TaskProblem.SENDER_UNAVAILABLE);
        assertThat(item(notCollected, second).note()).isEqualTo("Closed");

        AssignmentView handedOver = fake.recordOutcome(new RecordOutcomeCommand(pickup.id(), first, courier,
                ItemOutcome.HANDED_OVER, null, null, null, "S. Silva", null));
        assertThat(item(handedOver, first).closedAt()).isEqualTo(NOW);
        assertThat(item(handedOver, first).receivedBy()).isEqualTo("S. Silva");
    }

    @Test
    void aReturnedParcelKeepsTheReasonOfTheFailedAttempt() {
        AssignmentView round = task(AssignmentType.DELIVERY);
        UUID parcel = round.items().getFirst().parcelId();
        fake.recordOutcome(command(round, parcel, ItemOutcome.LOADED));
        fake.recordOutcome(new RecordOutcomeCommand(round.id(), parcel, courier, ItemOutcome.FAILED, null,
                FailureReason.RECIPIENT_UNAVAILABLE, null, null, null));

        AssignmentView returned = fake.recordOutcome(new RecordOutcomeCommand(round.id(), parcel, courier,
                ItemOutcome.RETURNED, null, null, null, "K. Dias", null));

        assertThat(item(returned, parcel).outcome()).isEqualTo(ItemOutcome.RETURNED);
        assertThat(item(returned, parcel).failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(item(returned, parcel).isClosed()).isTrue();
    }

    @Test
    void refusesWhatTheContractRefuses() {
        AssignmentView pickup = task(AssignmentType.PICKUP);
        UUID parcel = pickup.items().getFirst().parcelId();

        assertCode(() -> fake.recordOutcome(new RecordOutcomeCommand(pickup.id(), parcel, otherCourier,
                ItemOutcome.COLLECTED, null, null, null, null, null)), NotFoundException.class, ErrorCode.NOT_FOUND);
        assertCode(() -> fake.recordOutcome(command(pickup, UUID.randomUUID(), ItemOutcome.COLLECTED)),
                NotFoundException.class, ErrorCode.NOT_FOUND);
        assertCode(() -> fake.recordOutcome(command(pickup, parcel, ItemOutcome.HANDED_OVER)),
                BusinessRuleException.class, ErrorCode.TASK_STEP_NOT_ALLOWED);
        assertCode(() -> fake.recordOutcome(command(pickup, parcel, ItemOutcome.LOADED)),
                BusinessRuleException.class, ErrorCode.TASK_STEP_NOT_ALLOWED);
        assertCode(() -> fake.complete(pickup.id(), courier),
                BusinessRuleException.class, ErrorCode.ASSIGNMENT_NOT_FINISHED);
        assertCode(() -> fake.complete(pickup.id(), otherCourier), NotFoundException.class, ErrorCode.NOT_FOUND);

        assertThat(fake.findByIdForCourier(pickup.id(), courier)).contains(pickup);
    }

    @Test
    void aCompletedTaskIsClosedAndShowsInTheHistory() {
        AssignmentView transfer = task(AssignmentType.TRANSFER);
        for (AssignmentItemView item : transfer.items()) {
            fake.recordOutcome(new RecordOutcomeCommand(transfer.id(), item.parcelId(), courier,
                    ItemOutcome.NOT_LOADED, TaskProblem.PARCEL_MISSING, null, null, null, null));
        }

        AssignmentView completed = fake.complete(transfer.id(), courier);

        assertThat(completed.status()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW);
        assertCode(() -> fake.complete(transfer.id(), courier), BusinessRuleException.class,
                ErrorCode.ASSIGNMENT_CLOSED);
        assertCode(() -> fake.recordOutcome(command(transfer, transfer.items().getFirst().parcelId(),
                ItemOutcome.LOADED)), BusinessRuleException.class, ErrorCode.ASSIGNMENT_CLOSED);

        Page<AssignmentView> history = fake.findClosedForCourier(courier, null, PageRequest.of(0, 20));
        assertThat(history.getContent()).extracting(AssignmentView::id).containsExactly(transfer.id());
        assertThat(fake.findClosedForCourier(courier, transfer.items().getFirst().parcelId(), PageRequest.of(0, 20))
                .getContent()).hasSize(1);
        assertThat(fake.findClosedForCourier(courier, UUID.randomUUID(), PageRequest.of(0, 20)).getContent())
                .isEmpty();
        assertThat(fake.findClosedForCourier(courier, null, PageRequest.of(1, 20)).getContent()).isEmpty();
        // still listed today (closed today), but no longer once the day is over
        assertThat(fake.findOpenForCourier(courier, START_OF_TODAY)).hasSize(3);
        assertThat(fake.findOpenForCourier(courier, NOW.plusSeconds(1))).hasSize(2);
    }

    @Test
    void outcomesInARolledBackTransactionAreUndoneNewestFirst() {
        AssignmentView pickup = task(AssignmentType.PICKUP);
        UUID parcel = pickup.items().getFirst().parcelId();

        TransactionSynchronizationManager.initSynchronization();
        fake.recordOutcome(command(pickup, parcel, ItemOutcome.COLLECTED));
        fake.recordOutcome(new RecordOutcomeCommand(pickup.id(), parcel, courier, ItemOutcome.HANDED_OVER, null,
                null, null, "S. Silva", null));
        FakeDeliveryApiTest.finish(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(fake.findByIdForCourier(pickup.id(), courier)).contains(pickup);
    }

    private AssignmentView task(AssignmentType type) {
        return fake.findOpenForCourier(courier, Instant.EPOCH).stream()
                .filter(task -> task.type() == type)
                .findFirst().orElseThrow();
    }

    private static AssignmentItemView item(AssignmentView task, UUID parcelId) {
        return task.items().stream().filter(item -> item.parcelId().equals(parcelId)).findFirst().orElseThrow();
    }

    private RecordOutcomeCommand command(AssignmentView task, UUID parcelId, ItemOutcome outcome) {
        return new RecordOutcomeCommand(task.id(), parcelId, courier, outcome, null, null, null, null, null);
    }

    private static void assertCode(Runnable call, Class<? extends ApiException> type, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(type)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(code));
    }
}
