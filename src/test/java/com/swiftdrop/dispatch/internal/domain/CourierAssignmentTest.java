package com.swiftdrop.dispatch.internal.domain;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
import static com.swiftdrop.shared.reference.District.COLOMBO;
import static com.swiftdrop.shared.reference.District.GAMPAHA;
import static com.swiftdrop.shared.reference.District.MATARA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The assignment's own rules, without a database: creating, recording outcomes and completing. */
class CourierAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final UUID COURIER = UUID.randomUUID();
    private static final UUID DISPATCHER = UUID.randomUUID();

    // ---- creating ----

    @Test
    void aNewAssignmentIsAssignedWithPendingItemsInOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(first, second));

        AssignmentView view = assignment.toView();
        assertThat(view.status()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(view.courierId()).isEqualTo(COURIER);
        assertThat(view.destinationBranch()).isEqualTo(GAMPAHA);
        assertThat(view.assignedAt()).isEqualTo(NOW);
        assertThat(view.startedAt()).isNull();
        assertThat(view.version()).isZero();
        assertThat(view.items()).extracting(AssignmentItemView::parcelId).containsExactly(first, second);
        assertThat(view.items()).extracting(AssignmentItemView::sequence).containsExactly(1, 2);
        assertThat(view.items()).extracting(AssignmentItemView::outcome).containsOnly(PENDING);
        assertThat(view.items()).allMatch(item -> !item.isClosed() && item.assignmentId().equals(view.id()));
    }

    @Test
    void theBranchesMustFitTheType() {
        assertField(() -> create(PICKUP, null, null, one()), "destinationBranch");
        assertField(() -> create(PICKUP, COLOMBO, GAMPAHA, one()), "originBranch");
        assertField(() -> create(TRANSFER, null, MATARA, one()), "originBranch");
        assertField(() -> create(TRANSFER, GAMPAHA, null, one()), "destinationBranch");
        assertField(() -> create(TRANSFER, GAMPAHA, GAMPAHA, one()), "destinationBranch");
        assertField(() -> create(DELIVERY, null, null, one()), "originBranch");
        assertField(() -> create(DELIVERY, MATARA, GAMPAHA, one()), "destinationBranch");

        assertThat(create(TRANSFER, GAMPAHA, MATARA, one()).getType()).isEqualTo(TRANSFER);
        assertThat(create(DELIVERY, MATARA, null, one()).getType()).isEqualTo(DELIVERY);
    }

    @Test
    void theParcelListMustBeUsable() {
        UUID parcel = UUID.randomUUID();
        assertField(() -> create(PICKUP, null, GAMPAHA, List.of()), "parcelIds");
        assertField(() -> create(PICKUP, null, GAMPAHA, null), "parcelIds");
        assertField(() -> create(PICKUP, null, GAMPAHA, List.of(parcel, parcel)), "parcelIds");
        assertField(() -> create(PICKUP, null, GAMPAHA, Collections.singletonList(null)), "parcelIds");
        List<UUID> tooMany = Stream.generate(UUID::randomUUID).limit(CourierAssignment.MAX_ITEMS + 1).toList();
        assertField(() -> create(PICKUP, null, GAMPAHA, tooMany), "parcelIds");

        List<UUID> justEnough = Stream.generate(UUID::randomUUID).limit(CourierAssignment.MAX_ITEMS).toList();
        assertThat(create(PICKUP, null, GAMPAHA, justEnough).getItems()).hasSize(CourierAssignment.MAX_ITEMS);
    }

    // ---- recording outcomes ----

    @Test
    void theFirstOutcomeStartsTheAssignment() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(parcel, UUID.randomUUID()));

        assignment.recordOutcome(command(assignment, parcel, COLLECTED), NOW.plusSeconds(60));

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        assertThat(assignment.getStartedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(assignment.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(assignment.item(parcel).getOutcome()).isEqualTo(COLLECTED);
        assertThat(assignment.item(parcel).isClosed()).isFalse();
    }

    @Test
    void aPickupIsCollectedAndHandedOverAtTheBranch() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(parcel));
        assignment.recordOutcome(command(assignment, parcel, COLLECTED), NOW.plusSeconds(60));

        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, HANDED_OVER, null,
                null, "Left at the counter", "Sunil", null), NOW.plusSeconds(600));

        AssignmentItem item = assignment.item(parcel);
        assertThat(item.getOutcome()).isEqualTo(HANDED_OVER);
        assertThat(item.getReceivedBy()).isEqualTo("Sunil");
        assertThat(item.getNote()).isEqualTo("Left at the counter");
        assertThat(item.getClosedAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.IN_PROGRESS);
    }

    @Test
    void aParcelThatWasNotCollectedKeepsTheProblemAndIsClosed() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(parcel));

        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, NOT_COLLECTED,
                TaskProblem.SENDER_UNAVAILABLE, null, null, null, null), NOW);

        AssignmentItem item = assignment.item(parcel);
        assertThat(item.getProblem()).isEqualTo(TaskProblem.SENDER_UNAVAILABLE);
        assertThat(item.isClosed()).isTrue();
    }

    @Test
    void aFailedDeliveryKeepsItsReasonWhenTheParcelIsReturned() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(DELIVERY, MATARA, null, List.of(parcel));
        assignment.recordOutcome(command(assignment, parcel, LOADED), NOW);
        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, FAILED, null,
                FailureReason.RECIPIENT_UNAVAILABLE, "No answer", null, null), NOW.plusSeconds(60));

        AssignmentItem failed = assignment.item(parcel);
        assertThat(failed.getFailureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(failed.isClosed()).as("the courier still holds a failed parcel").isFalse();

        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, RETURNED, null,
                null, null, "Branch clerk", null), NOW.plusSeconds(120));

        AssignmentItem returned = assignment.item(parcel);
        assertThat(returned.getFailureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(returned.getNote()).as("no new note keeps the last one").isEqualTo("No answer");
        assertThat(returned.getReceivedBy()).isEqualTo("Branch clerk");
        assertThat(returned.isClosed()).isTrue();
    }

    @Test
    void aDeliveredParcelKeepsTheRecipientAndIsClosed() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(DELIVERY, MATARA, null, List.of(parcel));
        assignment.recordOutcome(command(assignment, parcel, LOADED), NOW);

        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, DELIVERED, null,
                null, null, null, "Kamala Silva"), NOW.plusSeconds(60));

        AssignmentItem item = assignment.item(parcel);
        assertThat(item.getRecipientName()).isEqualTo("Kamala Silva");
        assertThat(item.isClosed()).isTrue();
    }

    @Test
    void aStepTheTypeDoesNotAllowIsRejected() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment pickup = create(PICKUP, null, GAMPAHA, List.of(parcel));
        assertCode(() -> pickup.recordOutcome(command(pickup, parcel, HANDED_OVER), NOW),
                ErrorCode.TASK_STEP_NOT_ALLOWED);
        assertCode(() -> pickup.recordOutcome(command(pickup, parcel, LOADED), NOW),
                ErrorCode.TASK_STEP_NOT_ALLOWED);
        assertThat(pickup.getStatus()).as("a refused step starts nothing").isEqualTo(AssignmentStatus.ASSIGNED);

        UUID transferred = UUID.randomUUID();
        CourierAssignment transfer = create(TRANSFER, GAMPAHA, MATARA, List.of(transferred));
        transfer.recordOutcome(command(transfer, transferred, LOADED), NOW);
        assertCode(() -> transfer.recordOutcome(command(transfer, transferred, LOADED), NOW),
                ErrorCode.TASK_STEP_NOT_ALLOWED);
    }

    @Test
    void anotherCouriersAssignmentOrParcelIsNotFound() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(parcel));

        assertThatThrownBy(() -> assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel,
                UUID.randomUUID(), COLLECTED, null, null, null, null, null), NOW))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> assignment.recordOutcome(new RecordOutcomeCommand(UUID.randomUUID(), parcel,
                COURIER, COLLECTED, null, null, null, null, null), NOW))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> assignment.recordOutcome(command(assignment, UUID.randomUUID(), COLLECTED), NOW))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anOutcomeWithoutTheFieldItNeedsIsAProgrammingError() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment pickup = create(PICKUP, null, GAMPAHA, List.of(parcel));
        assertThatThrownBy(() -> pickup.recordOutcome(command(pickup, parcel, NOT_COLLECTED), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        UUID delivered = UUID.randomUUID();
        CourierAssignment delivery = create(DELIVERY, MATARA, null, List.of(delivered));
        delivery.recordOutcome(command(delivery, delivered, LOADED), NOW);
        assertThatThrownBy(() -> delivery.recordOutcome(command(delivery, delivered, FAILED), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.recordOutcome(command(delivery, delivered, DELIVERED), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- completing ----

    @Test
    void itCompletesOnlyWhenEveryItemIsClosed() {
        UUID taken = UUID.randomUUID();
        UUID notReady = UUID.randomUUID();
        CourierAssignment assignment = create(TRANSFER, GAMPAHA, MATARA, List.of(taken, notReady));
        assignment.recordOutcome(command(assignment, taken, LOADED), NOW);
        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), notReady, COURIER, NOT_LOADED,
                TaskProblem.PARCEL_MISSING, null, null, null, null), NOW);

        assertCode(() -> assignment.complete(NOW.plusSeconds(60)), ErrorCode.ASSIGNMENT_NOT_FINISHED);

        assignment.recordOutcome(command(assignment, taken, HANDED_OVER), NOW.plusSeconds(3600));
        assignment.complete(NOW.plusSeconds(3700));

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignment.getCompletedAt()).isEqualTo(NOW.plusSeconds(3700));
        assertThat(assignment.closedAt()).isEqualTo(NOW.plusSeconds(3700));
    }

    @Test
    void nothingCanBeRecordedOnACompletedAssignment() {
        UUID parcel = UUID.randomUUID();
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, List.of(parcel));
        assignment.recordOutcome(new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, NOT_COLLECTED,
                TaskProblem.PARCEL_NOT_READY, null, null, null, null), NOW);
        assignment.complete(NOW);

        assertCode(() -> assignment.complete(NOW), ErrorCode.ASSIGNMENT_CLOSED);
        assertCode(() -> assignment.recordOutcome(command(assignment, parcel, COLLECTED), NOW),
                ErrorCode.ASSIGNMENT_CLOSED);
    }

    @Test
    void theItemListCannotBeChangedFromOutside() {
        CourierAssignment assignment = create(PICKUP, null, GAMPAHA, one());
        assertThatThrownBy(() -> assignment.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- helpers ----

    static CourierAssignment create(AssignmentType type, District origin, District destination, List<UUID> parcels) {
        return CourierAssignment.create(UUID.randomUUID(), COURIER, type, origin, destination, TODAY, null,
                DISPATCHER, parcels, NOW);
    }

    private static List<UUID> one() {
        return List.of(UUID.randomUUID());
    }

    private static RecordOutcomeCommand command(CourierAssignment assignment, UUID parcel, ItemOutcome outcome) {
        return new RecordOutcomeCommand(assignment.getId(), parcel, COURIER, outcome, null, null, null, null, null);
    }

    private static void assertField(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey(field));
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
