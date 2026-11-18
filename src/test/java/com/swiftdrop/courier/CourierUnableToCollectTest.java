package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Unable to Collect" ({@code POST /courier/deliveries/{parcelId}/unable-to-collect}): one action for both task types.
 * It records NOT_COLLECTED for a pickup and NOT_LOADED for a delivery, never moves the parcel, completes the parcel for
 * the courier, and completes the task only when it was the last open parcel.
 */
class CourierUnableToCollectTest extends CourierApiTest {

    @Test
    void aPickupThatCannotBeCollectedIsNotCollectedAndTheParcelStaysWhereItIs() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(jsonPath("$.canMarkUnableToCollect").value(true));

        postUnableToCollect(parcel.id(), Map.of("problem", "SENDER_UNAVAILABLE", "note", "  Shop closed  "),
                courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.task.outcome").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.task.problem").value("SENDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.nextStatuses").isEmpty())
                .andExpect(jsonPath("$.canMarkUnableToCollect").value(false))
                .andExpect(jsonPath("$.pickup").isEmpty());

        RecordOutcomeCommand command = sentOutcome();
        assertThat(command.outcome()).isEqualTo(ItemOutcome.NOT_COLLECTED);
        assertThat(command.problem()).isEqualTo(TaskProblem.SENDER_UNAVAILABLE);
        assertThat(command.note()).isEqualTo("Shop closed");
        assertThat(command.courierId()).isEqualTo(courier.id());
        // The parcel never moved, so delivery is not asked to change it.
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void aDeliveryThatCannotBeTakenOutIsNotLoadedAndStaysAtTheBranch() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.AT_BRANCH));

        postUnableToCollect(parcel.id(), Map.of("problem", "PARCEL_DAMAGED"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.stop").value("DELIVERY"))
                .andExpect(jsonPath("$.task.outcome").value("NOT_LOADED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.recipient").isEmpty());

        assertThat(sentOutcome().outcome()).isEqualTo(ItemOutcome.NOT_LOADED);
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void theTaskCompletesOnlyWhenThisWasItsLastOpenParcel() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot first = parcel(courier.id(), ParcelStatus.CREATED);
        DeliverySnapshot second = parcel(courier.id(), ParcelStatus.CREATED);
        AssignmentView task = givenTask(courier.id(), AssignmentType.PICKUP,
                step(first, ItemOutcome.PENDING), step(second, ItemOutcome.PENDING));

        postUnableToCollect(first.id(), Map.of("problem", "PARCEL_NOT_READY"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        assertThat(task(task.id()).status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        verify(assignments, never()).complete(any(), any());

        postUnableToCollect(second.id(), Map.of("problem", "SENDER_UNAVAILABLE"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        verify(assignments).complete(task.id(), courier.id());
    }

    @Test
    void aParcelAlreadyTakenCannotBeUnableToCollect() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot collected = given(parcel(courier.id(), ParcelStatus.PICKED_UP));
        DeliverySnapshot outForDelivery = given(parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY));
        DeliverySnapshot failed = given(parcel(courier.id(), ParcelStatus.FAILED));

        for (UUID parcelId : new UUID[]{collected.id(), outForDelivery.id(), failed.id()}) {
            mvc.perform(get(BASE + "/{id}", parcelId).cookie(courier.cookie()))
                    .andExpect(jsonPath("$.canMarkUnableToCollect").value(false));
            postUnableToCollect(parcelId, Map.of("problem", "OTHER", "note", "Changed my mind"), courier.cookie())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        }
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void aClosedTaskIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot delivered = given(parcel(courier.id(), ParcelStatus.DELIVERED));

        postUnableToCollect(delivered.id(), Map.of("problem", "PARCEL_MISSING"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void anotherCouriersOrAnUnknownParcelIs404() throws Exception {
        Courier owner = registerCourier();
        Courier other = registerCourier();
        DeliverySnapshot parcel = given(parcel(owner.id(), ParcelStatus.CREATED));

        postUnableToCollect(parcel.id(), Map.of("problem", "SENDER_UNAVAILABLE"), other.cookie())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postUnableToCollect(UUID.randomUUID(), Map.of("problem", "SENDER_UNAVAILABLE"), owner.cookie())
                .andExpect(status().isNotFound());
        verify(assignments, never()).recordOutcome(any());
    }

    // ---- Request rules (400) ----

    @Test
    void aReasonIsRequired() throws Exception {
        expectFieldError(Map.of("note", "Nobody home"), "problem", "Select why you couldn't take the parcel.");
    }

    @Test
    void otherNeedsANote() throws Exception {
        expectFieldError(Map.of("problem", "OTHER"), "note", "Describe what went wrong.");
        expectFieldError(Map.of("problem", "OTHER", "note", " ab "), "note", "Describe what went wrong.");
    }

    @Test
    void tooLongNoteOrUnknownReasonIs400() throws Exception {
        expectFieldError(Map.of("problem", "SENDER_UNAVAILABLE", "note", "x".repeat(501)),
                "note", "Keep the note under 500 characters.");
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));
        mvc.perform(post(BASE + "/{id}/unable-to-collect", parcel.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"RAIN\"}")
                        .cookie(courier.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.problem").exists());
        verify(assignments, never()).recordOutcome(any());
    }

    private void expectFieldError(Map<String, ?> body, String field, String message) throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));

        postUnableToCollect(parcel.id(), body, courier.cookie())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verify(assignments, never()).recordOutcome(any());
    }

    private RecordOutcomeCommand sentOutcome() {
        ArgumentCaptor<RecordOutcomeCommand> captor = ArgumentCaptor.forClass(RecordOutcomeCommand.class);
        verify(assignments).recordOutcome(captor.capture());
        return captor.getValue();
    }
}
