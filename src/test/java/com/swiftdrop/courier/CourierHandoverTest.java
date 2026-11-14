package com.swiftdrop.courier;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Handing parcels over at a branch: in bulk, all or nothing; failed deliveries are RETURNED to the origin. */
class CourierHandoverTest extends CourierApiTest {

    @Test
    void aPickupHandsTheCollectedParcelsOverAtTheDestinationBranch() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot first = parcel(ParcelStatus.PICKED_UP);
        DeliverySnapshot second = parcel(ParcelStatus.PICKED_UP);
        DeliverySnapshot notCollected = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(first, ItemOutcome.COLLECTED), item(second, ItemOutcome.COLLECTED),
                item(notCollected, ItemOutcome.NOT_COLLECTED)), first, second, notCollected);

        postHandover(task, Map.of("parcelIds", List.of(first.id(), second.id()), "receivedBy", "  S. Silva  ",
                "note", "Counter 2"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("AT_BRANCH"))
                .andExpect(jsonPath("$.items[0].receivedBy").value("S. Silva"))
                .andExpect(jsonPath("$.items[0].readyForHandover").value(false))
                .andExpect(jsonPath("$.items[1].outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.items[2].outcome").value("NOT_COLLECTED"))
                // every parcel is closed now, although one was never collected
                .andExpect(jsonPath("$.counts.open").value(0))
                .andExpect(jsonPath("$.canComplete").value(true));

        List<ChangeStatusCommand> changes = sentStatusChanges();
        assertThat(changes).extracting(ChangeStatusCommand::parcelId).containsExactly(first.id(), second.id());
        assertThat(changes).allSatisfy(change -> {
            assertThat(change.toStatus()).isEqualTo(ParcelStatus.AT_BRANCH);
            assertThat(change.branch()).isEqualTo(District.GAMPAHA);
            assertThat(change.note()).isEqualTo("Counter 2");
            assertThat(change.actorRole()).isEqualTo("COURIER");
        });
        List<RecordOutcomeCommand> outcomes = sentOutcomes();
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.outcome()).isEqualTo(ItemOutcome.HANDED_OVER);
            assertThat(outcome.receivedBy()).isEqualTo("S. Silva");
            assertThat(outcome.courierId()).isEqualTo(courier.id());
        });
        assertThat(current(notCollected).status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void aTransferHandsOverAtTheDestinationBranch() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.IN_TRANSIT);
        AssignmentView task = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.IN_PROGRESS,
                item(parcel, ItemOutcome.LOADED)), parcel);

        postHandover(task, Map.of("parcelIds", List.of(parcel.id()), "receivedBy", "R. Perera"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("HANDED_OVER"));
        assertThat(current(parcel).currentBranch()).isEqualTo(District.MATARA);
    }

    @Test
    void failedDeliveriesAreReturnedToTheOriginBranch() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot failed = parcel(ParcelStatus.FAILED);
        DeliverySnapshot delivered = parcel(ParcelStatus.DELIVERED);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(failed, ItemOutcome.FAILED), item(delivered, ItemOutcome.DELIVERED)), failed, delivered);

        postHandover(task, Map.of("parcelIds", List.of(failed.id()), "receivedBy", "K. Dias"), courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("RETURNED"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("AT_BRANCH"))
                // the reason of the failed attempt stays on the item
                .andExpect(jsonPath("$.items[0].failureReason").value("RECIPIENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.canComplete").value(true));

        ChangeStatusCommand change = sentStatusChanges().getFirst();
        assertThat(change.branch()).isEqualTo(District.MATARA);
        assertThat(change.failureReason()).isNull();
        RecordOutcomeCommand outcome = sentOutcomes().getFirst();
        assertThat(outcome.outcome()).isEqualTo(ItemOutcome.RETURNED);
        assertThat(outcome.failureReason()).isNull();
        assertThat(current(failed).failureReason()).isNull();
    }

    @Test
    void oneParcelThatIsNotReadyStopsTheWholeHandover() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot collected = parcel(ParcelStatus.PICKED_UP);
        DeliverySnapshot pending = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(collected, ItemOutcome.COLLECTED), item(pending, ItemOutcome.PENDING)), collected, pending);

        postHandover(task, Map.of("parcelIds", List.of(collected.id(), pending.id()), "receivedBy", "S. Silva"),
                courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void aDeliveredParcelCannotBeHandedOver() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot delivered = parcel(ParcelStatus.DELIVERED);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(delivered, ItemOutcome.DELIVERED)), delivered);

        postHandover(task, Map.of("parcelIds", List.of(delivered.id()), "receivedBy", "K. Dias"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
    }

    @Test
    void aParcelThatCannotGoToTheBranchStopsTheWholeHandover() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot ready = parcel(ParcelStatus.PICKED_UP);
        // dispatch says collected, but delivery never recorded the pickup
        DeliverySnapshot elsewhere = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(ready, ItemOutcome.COLLECTED), item(elsewhere, ItemOutcome.COLLECTED)), ready, elsewhere);

        postHandover(task, Map.of("parcelIds", List.of(ready.id(), elsewhere.id()), "receivedBy", "S. Silva"),
                courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void aClosedTaskIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.PICKED_UP);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.CANCELLED,
                item(parcel, ItemOutcome.COLLECTED)), parcel);

        postHandover(task, Map.of("parcelIds", List.of(parcel.id()), "receivedBy", "S. Silva"), courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
    }

    // ---- Request rules (400) ----

    @Test
    void theParcelListMustHoldOneTo100DifferentParcels() throws Exception {
        UUID one = UUID.randomUUID();
        expectFieldError("{\"parcelIds\":[],\"receivedBy\":\"S. Silva\"}", "parcelIds",
                "Select at least one parcel.");
        expectFieldError("{\"receivedBy\":\"S. Silva\"}", "parcelIds", "Select at least one parcel.");
        List<String> many = new ArrayList<>(Collections.nCopies(101, "\"" + one + "\""));
        expectFieldError("{\"parcelIds\":" + many + ",\"receivedBy\":\"S. Silva\"}", "parcelIds",
                "Hand over at most 100 parcels at a time.");
        expectFieldError("{\"parcelIds\":[\"" + one + "\",\"" + one + "\"],\"receivedBy\":\"S. Silva\"}",
                "parcelIds", "Select each parcel only once.");
    }

    @Test
    void whoReceivedTheParcelsIsRequired() throws Exception {
        String ids = "\"parcelIds\":[\"" + UUID.randomUUID() + "\"]";
        String message = "Enter the name of the person at the branch who received the parcels.";
        expectFieldError("{" + ids + "}", "receivedBy", message);
        expectFieldError("{" + ids + ",\"receivedBy\":\" S \"}", "receivedBy", message);
        expectFieldError("{" + ids + ",\"receivedBy\":\"" + "x".repeat(101) + "\"}", "receivedBy", message);
        expectFieldError("{" + ids + ",\"receivedBy\":\"S. Silva\",\"note\":\"" + "x".repeat(501) + "\"}", "note",
                "Keep the note under 500 characters.");
    }

    private void expectFieldError(String body, String field, String message) throws Exception {
        Courier courier = registerCourier();

        mvc.perform(post(BASE + "/{id}/handover", UUID.randomUUID()).cookie(courier.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }
}
