package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

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
 * A dispatcher groups several parcels in one task; the courier works each parcel on its own. Each parcel is
 * {@code completed} as soon as its own item closes (dispatch's {@code ItemOutcome.closes}); the task becomes COMPLETED
 * by itself only when its last parcel closes, through dispatch's {@code complete}. There is no manual "complete task".
 */
class CourierAutoCompleteTest extends CourierApiTest {

    @Test
    void aPickupCompletesOnlyAfterEveryCollectedParcelIsHandedOver() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot first = parcel(courier.id(), ParcelStatus.CREATED);
        DeliverySnapshot second = parcel(courier.id(), ParcelStatus.CREATED);
        AssignmentView task = givenTask(courier.id(), AssignmentType.PICKUP,
                step(first, ItemOutcome.PENDING), step(second, ItemOutcome.PENDING));

        // Collected is not completed: the handover is still to come.
        postStatus(first.id(), Map.of("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));

        // Handed over: this parcel is completed, the task is not (the second parcel is still waiting).
        postStatus(first.id(), Map.of("status", "AT_BRANCH", "receivedBy", "S. Silva"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        verify(assignments, never()).complete(any(), any());
        assertThat(current(first.id()).currentBranch()).isEqualTo(District.COLOMBO);

        // The last parcel can't be collected: now every parcel is closed, so the task completes by itself.
        postUnableToCollect(second.id(), Map.of("problem", "SENDER_UNAVAILABLE"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        verify(assignments).complete(task.id(), courier.id());
        assertThat(task(task.id()).status()).isEqualTo(AssignmentStatus.COMPLETED);
    }

    @Test
    void aCollectedParcelKeepsTheTaskOpenUntilItIsHandedOver() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot collected = parcel(courier.id(), ParcelStatus.CREATED);
        DeliverySnapshot notCollected = parcel(courier.id(), ParcelStatus.CREATED);
        AssignmentView task = givenTask(courier.id(), AssignmentType.PICKUP,
                step(collected, ItemOutcome.PENDING), step(notCollected, ItemOutcome.PENDING));

        postStatus(collected.id(), Map.of("status", "PICKED_UP"), courier.cookie()).andExpect(status().isOk());
        // Every other parcel is closed, but the collected one still has to be handed over.
        postUnableToCollect(notCollected.id(), Map.of("problem", "PARCEL_NOT_READY"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        verify(assignments, never()).complete(any(), any());

        postStatus(collected.id(), Map.of("status", "AT_BRANCH", "receivedBy", "S. Silva"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        verify(assignments).complete(task.id(), courier.id());
    }

    @Test
    void aFailedDeliveryKeepsTheTaskOpenUntilItIsReturned() throws Exception {
        Courier courier = registerCourier();
        deliveryAppliesChanges();
        DeliverySnapshot delivered = parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY);
        DeliverySnapshot failed = parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY);
        AssignmentView task = givenTask(courier.id(), AssignmentType.DELIVERY,
                step(delivered, ItemOutcome.LOADED), step(failed, ItemOutcome.LOADED));

        postStatus(delivered.id(), Map.of("status", "DELIVERED", "recipientName", "Nimal Perera"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));

        // FAILED is not completed: the courier still holds the parcel and must return it.
        postStatus(failed.id(), Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.nextStatuses[0]").value("AT_BRANCH"))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        verify(assignments, never()).complete(any(), any());

        postStatus(failed.id(), Map.of("status", "AT_BRANCH", "receivedBy", "N. Fernando"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.outcome").value("RETURNED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        verify(assignments).complete(task.id(), courier.id());
        assertThat(current(failed.id()).status()).isEqualTo(ParcelStatus.AT_BRANCH);
    }

    @Test
    void todayShowsEachParcelOfOneTaskAsActiveOrCompletedOnItsOwn() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot handedOver = parcel(courier.id(), ParcelStatus.AT_BRANCH);
        DeliverySnapshot collected = parcel(courier.id(), ParcelStatus.PICKED_UP);
        DeliverySnapshot waiting = parcel(courier.id(), ParcelStatus.CREATED);
        AssignmentView task = givenTask(courier.id(), AssignmentType.PICKUP, step(handedOver, ItemOutcome.HANDED_OVER),
                step(collected, ItemOutcome.COLLECTED), step(waiting, ItemOutcome.PENDING));

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo").value(2))
                .andExpect(jsonPath("$.done").value(1))
                .andExpect(jsonPath("$.items.length()").value(3))
                // active parcels first, all from the same task
                .andExpect(jsonPath("$.items[0].completed").value(false))
                .andExpect(jsonPath("$.items[1].completed").value(false))
                .andExpect(jsonPath("$.items[2].parcelId").value(handedOver.id().toString()))
                .andExpect(jsonPath("$.items[2].completed").value(true))
                .andExpect(jsonPath("$.items[2].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[2].pickup").isEmpty())
                .andExpect(jsonPath("$.items[2].from.city").value("Nugegoda"))
                .andExpect(jsonPath("$.items[0].task.assignmentId").value(task.id().toString()))
                .andExpect(jsonPath("$.items[1].task.assignmentId").value(task.id().toString()))
                .andExpect(jsonPath("$.items[2].task.assignmentId").value(task.id().toString()))
                .andExpect(jsonPath("$.items[2].task.status").value("IN_PROGRESS"));
    }

    @Test
    void thereIsNoManualCompleteAndTheOldTaskEndpointsAreGone() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.PICKED_UP));
        UUID taskId = taskOf(parcel.id()).id();

        mvc.perform(post(BASE + "/{id}/complete", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/courier/assignments/{id}/complete", taskId).cookie(courier.cookie()))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/courier/assignments/today").cookie(courier.cookie()))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/courier/parcels/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().is4xxClientError());
        verify(assignments, never()).complete(any(), any());
    }
}
