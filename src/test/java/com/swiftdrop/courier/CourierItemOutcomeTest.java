package com.swiftdrop.courier;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recording what happened to one parcel: the parcel move per task type, the check order and the request rules. */
class CourierItemOutcomeTest extends CourierApiTest {

    // ---- The task detail ----

    @Test
    void aPickupShowsTheSenderAndWhatCanBeRecorded() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot first = parcel(ParcelStatus.CREATED);
        DeliverySnapshot second = parcel(ParcelStatus.PICKED_UP);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(first, ItemOutcome.PENDING), item(second, ItemOutcome.COLLECTED)), first, second);

        mvc.perform(get(BASE + "/{id}", task.id()).cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(task.id().toString()))
                .andExpect(jsonPath("$.type").value("PICKUP"))
                .andExpect(jsonPath("$.counts.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].parcelId").value(first.id().toString()))
                .andExpect(jsonPath("$.items[0].trackingNumber").value(first.trackingNumber()))
                .andExpect(jsonPath("$.items[0].sequence").value(1))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("CREATED"))
                .andExpect(jsonPath("$.items[0].outcome").value("PENDING"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("COLLECTED"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[1]").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.items[0].readyForHandover").value(false))
                .andExpect(jsonPath("$.items[0].contact.name").value("Delgoda Spice Traders"))
                .andExpect(jsonPath("$.items[0].contact.phone").value("+94760000101"))
                .andExpect(jsonPath("$.items[0].from.city").value("Delgoda"))
                .andExpect(jsonPath("$.items[0].to.city").value("Deniyaya"))
                .andExpect(jsonPath("$.items[0].codAmount").isEmpty())
                .andExpect(jsonPath("$.items[0].instructions").isEmpty())
                // collected: only the handover is left, which is not an outcome the courier picks here
                .andExpect(jsonPath("$.items[1].allowedOutcomes").isEmpty())
                .andExpect(jsonPath("$.items[1].readyForHandover").value(true));
    }

    @Test
    void aDeliveryShowsTheRecipientAndTheCashAndATransferShowsNoContact() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.MATARA);
        AssignmentView round = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);
        DeliverySnapshot moving = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView transfer = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                item(moving, ItemOutcome.PENDING)), moving);

        mvc.perform(get(BASE + "/{id}", round.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.items[0].contact.name").value("Nimal Perera"))
                .andExpect(jsonPath("$.items[0].codAmount").value(2450.00))
                .andExpect(jsonPath("$.items[0].instructions").value("Call before you arrive."))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("LOADED"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[1]").value("NOT_LOADED"));
        mvc.perform(get(BASE + "/{id}", transfer.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.items[0].contact").isEmpty())
                .andExpect(jsonPath("$.items[0].codAmount").isEmpty());
    }

    // ---- The parcel move per type ----

    @Test
    void collectedPicksTheParcelUpAndRecordsTheOutcome() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED", "note", "  From the front desk  "), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].outcome").value("COLLECTED"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.items[0].readyForHandover").value(true))
                .andExpect(jsonPath("$.items[0].note").value("From the front desk"));

        ChangeStatusCommand change = sentStatusChanges().getFirst();
        assertThat(change.parcelId()).isEqualTo(parcel.id());
        assertThat(change.toStatus()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(change.actorId()).isEqualTo(courier.id());
        assertThat(change.actorRole()).isEqualTo("COURIER");
        assertThat(change.note()).isEqualTo("From the front desk");
        assertThat(change.branch()).isNull();
        RecordOutcomeCommand outcome = sentOutcomes().getFirst();
        assertThat(outcome.assignmentId()).isEqualTo(task.id());
        assertThat(outcome.parcelId()).isEqualTo(parcel.id());
        assertThat(outcome.courierId()).isEqualTo(courier.id());
        assertThat(outcome.outcome()).isEqualTo(ItemOutcome.COLLECTED);
        assertThat(outcome.note()).isEqualTo("From the front desk");
    }

    @Test
    void notCollectedLeavesTheParcelAloneAndRecordsTheProblem() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "NOT_COLLECTED", "problem", "SENDER_UNAVAILABLE",
                "note", "Shop closed"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.items[0].problem").value("SENDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("CREATED"))
                // closed: no more contact details
                .andExpect(jsonPath("$.items[0].contact").isEmpty())
                .andExpect(jsonPath("$.canComplete").value(true));

        verify(delivery, never()).changeStatus(any());
        assertThat(sentOutcomes().getFirst().problem()).isEqualTo(TaskProblem.SENDER_UNAVAILABLE);
    }

    @Test
    void loadingATransferSendsTheParcelInTransit() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView task = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "LOADED"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("IN_TRANSIT"));
        assertThat(sentStatusChanges().getFirst().toStatus()).isEqualTo(ParcelStatus.IN_TRANSIT);
    }

    @Test
    void loadingAParcelThatIsAtAnotherBranchIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.KANDY);
        AssignmentView task = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "LOADED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void aDeliveryLoadsThenDeliversWithTheRecipientsName() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.MATARA);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "LOADED"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("DELIVERED"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[1]").value("FAILED"));
        postOutcome(task, parcel, Map.of("outcome", "DELIVERED", "recipientName", " Nimal Perera "), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.items[0].recipientName").value("Nimal Perera"))
                .andExpect(jsonPath("$.canComplete").value(true));

        ChangeStatusCommand delivered = sentStatusChanges().get(1);
        assertThat(delivered.toStatus()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(delivered.recipientName()).isEqualTo("Nimal Perera");
        assertThat(sentOutcomes().get(1).recipientName()).isEqualTo("Nimal Perera");
    }

    @Test
    void aFailedDeliveryKeepsTheParcelWithTheCourierUntilItIsReturned() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.OUT_FOR_DELIVERY);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(parcel, ItemOutcome.LOADED)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE",
                "note", "No answer, called twice"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("FAILED"))
                .andExpect(jsonPath("$.items[0].failureReason").value("RECIPIENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes").isEmpty())
                .andExpect(jsonPath("$.items[0].readyForHandover").value(true))
                .andExpect(jsonPath("$.canComplete").value(false));

        ChangeStatusCommand change = sentStatusChanges().getFirst();
        assertThat(change.failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        assertThat(change.note()).isEqualTo("No answer, called twice");
    }

    @Test
    void otherReasonOrProblemWithANoteIsAccepted() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.OUT_FOR_DELIVERY);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(parcel, ItemOutcome.LOADED)), parcel);
        DeliverySnapshot other = parcel(ParcelStatus.CREATED);
        AssignmentView pickup = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(other, ItemOutcome.PENDING)), other);

        postOutcome(task, parcel, Map.of("outcome", "FAILED", "failureReason", "OTHER", "note", "Gate locked"),
                courier.cookie())
                .andExpect(status().isOk());
        postOutcome(pickup, other, Map.of("outcome", "NOT_COLLECTED", "problem", "OTHER", "note", "Flooded road"),
                courier.cookie())
                .andExpect(status().isOk());
    }

    // ---- Check order (409) ----

    @Test
    void aClosedTaskIs409BeforeTheStepIsChecked() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                item(parcel, ItemOutcome.HANDED_OVER)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"))
                .andExpect(jsonPath("$.message").value("This task is already completed or was cancelled."));
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void aStepThatTheTaskDoesNotAllowIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.MATARA);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        // load first
        postOutcome(task, parcel, Map.of("outcome", "DELIVERED", "recipientName", "Nimal Perera"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message")
                        .value("This step isn't possible for this parcel now. Refresh to see your task."));
        // a pickup outcome in a delivery task
        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void pressingTheButtonTwiceIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie()).andExpect(status().isOk());
        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
    }

    @Test
    void aParcelThatCannotMakeTheMoveIs409() throws Exception {
        Courier courier = registerCourier();
        // dispatch says pending, but the parcel was already picked up elsewhere
        DeliverySnapshot parcel = parcel(ParcelStatus.PICKED_UP);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void aChangeThatDeliveryRefusesIs409AndTheOutcomeIsNotRecorded() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);
        doThrow(new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION)).when(delivery).changeStatus(any());

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void aConcurrentChangeIs409InsteadOf500() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);
        doThrow(new ObjectOptimisticLockingFailureException("AssignmentItem", UUID.randomUUID()))
                .when(assignments).recordOutcome(any());

        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
    }

    // ---- Request rules (400) ----

    @Test
    void missingOrPendingOutcomeIs400() throws Exception {
        expectFieldError(Map.of("note", "Collected"), "outcome", "Select what happened.");
        expectFieldError(Map.of("outcome", "PENDING"), "outcome", "Select what happened.");
    }

    @Test
    void handoverOutcomesGoThroughTheHandover() throws Exception {
        expectFieldError(Map.of("outcome", "HANDED_OVER"), "outcome", "Use Hand over for this step.");
        expectFieldError(Map.of("outcome", "RETURNED"), "outcome", "Use Hand over for this step.");
    }

    @Test
    void aProblemIsNeededOnlyWhenTheParcelWasNotTaken() throws Exception {
        expectFieldError(Map.of("outcome", "NOT_COLLECTED"), "problem", "Select why you couldn't take the parcel.");
        expectFieldError(Map.of("outcome", "NOT_LOADED"), "problem", "Select why you couldn't take the parcel.");
        expectFieldError(Map.of("outcome", "COLLECTED", "problem", "PARCEL_DAMAGED"), "problem",
                "Only when a parcel couldn't be collected or loaded.");
        expectFieldError(Map.of("outcome", "NOT_COLLECTED", "problem", "OTHER", "note", " x "), "note",
                "Describe what went wrong.");
    }

    @Test
    void failedNeedsAReasonAndOtherNeedsANote() throws Exception {
        expectFieldError(Map.of("outcome", "FAILED"), "failureReason", "Select why the delivery failed.");
        expectFieldError(Map.of("outcome", "FAILED", "failureReason", "OTHER", "note", "  "), "note",
                "Describe what went wrong.");
        expectFieldError(Map.of("outcome", "DELIVERED", "recipientName", "Nimal Perera",
                "failureReason", "WRONG_ADDRESS"), "failureReason", "Only for failed deliveries.");
    }

    @Test
    void deliveredNeedsTheRecipientsName() throws Exception {
        expectFieldError(Map.of("outcome", "DELIVERED"), "recipientName",
                "Enter the name of the person who received the parcel.");
        expectFieldError(Map.of("outcome", "DELIVERED", "recipientName", " N "), "recipientName",
                "Enter the name of the person who received the parcel.");
        expectFieldError(Map.of("outcome", "COLLECTED", "recipientName", "Nimal Perera"), "recipientName",
                "Only for delivered parcels.");
    }

    @Test
    void tooLongNoteOrNameIs400() throws Exception {
        expectFieldError(Map.of("outcome", "COLLECTED", "note", "x".repeat(501)), "note",
                "Keep the note under 500 characters.");
        expectFieldError(Map.of("outcome", "DELIVERED", "recipientName", "x".repeat(101)), "recipientName",
                "Enter the name of the person who received the parcel.");
    }

    @Test
    void unknownValuesAre400() throws Exception {
        Courier courier = registerCourier();
        UUID taskId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();

        postOutcome(taskId, parcelId, "{\"outcome\":\"LOST\"}", courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.outcome").exists());
        postOutcome(taskId, parcelId, "{\"outcome\":\"FAILED\",\"failureReason\":\"RAIN\"}", courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.failureReason").exists());
        postOutcome(taskId, parcelId, "{\"outcome\":\"NOT_LOADED\",\"problem\":\"LATE\"}", courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.problem").exists());
    }

    /** The request is checked before ownership, so any task id (even an unknown one) gives the 400. */
    private void expectFieldError(Map<String, ?> body, String field, String message) throws Exception {
        Courier courier = registerCourier();

        postOutcome(UUID.randomUUID(), UUID.randomUUID(), json.writeValueAsString(body), courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }
}
