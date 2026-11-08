package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A courier reaches only their own tasks and the parcels in them; everything else looks like it doesn't exist (404). */
class CourierOwnershipTest extends CourierApiTest {

    @Test
    void anotherCouriersTaskIs404ForEveryEndpoint() throws Exception {
        Courier owner = registerCourier();
        Courier other = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(owner.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        mvc.perform(get(BASE + "/{id}", task.id()).cookie(other.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postOutcome(task, parcel, Map.of("outcome", "COLLECTED"), other.cookie())
                .andExpect(status().isNotFound());
        postHandover(task, Map.of("parcelIds", List.of(parcel.id()), "receivedBy", "S. Silva"), other.cookie())
                .andExpect(status().isNotFound());
        postComplete(task, other.cookie())
                .andExpect(status().isNotFound());

        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
        verify(assignments, never()).complete(any(), any());
    }

    @Test
    void unknownTaskIs404() throws Exception {
        Courier courier = registerCourier();

        mvc.perform(get(BASE + "/{id}", UUID.randomUUID()).cookie(courier.cookie()))
                .andExpect(status().isNotFound());
        postOutcome(UUID.randomUUID(), UUID.randomUUID(), "{\"outcome\":\"COLLECTED\"}", courier.cookie())
                .andExpect(status().isNotFound());
    }

    @Test
    void aParcelThatIsNotInMyTaskIs404AndNothingChanges() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot mine = parcel(ParcelStatus.CREATED);
        DeliverySnapshot notInTask = given(parcel(ParcelStatus.CREATED));
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(mine, ItemOutcome.COLLECTED)), mine);

        postOutcome(task, notInTask, Map.of("outcome", "COLLECTED"), courier.cookie())
                .andExpect(status().isNotFound());
        // one wrong parcel in a handover: nothing is handed over
        postHandover(task, Map.of("parcelIds", List.of(mine.id(), notInTask.id()), "receivedBy", "S. Silva"),
                courier.cookie())
                .andExpect(status().isNotFound());

        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void myParcelShowsItsHistoryWithoutNotesAndTheContactOfMyOpenItem() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(ParcelStatus.CREATED));
        AssignmentView pickup = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)));
        when(assignments.findItemsForCourierAndParcel(courier.id(), parcel.id()))
                .thenReturn(List.of(pickup.items().getFirst()));
        when(delivery.history(parcel.id())).thenReturn(List.of(new ParcelStatusEntry(ParcelStatus.CREATED,
                "Call Nimal on +94760000201", null, "MERCHANT", Instant.now())));

        mvc.perform(get(PARCELS + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(parcel.id().toString()))
                .andExpect(jsonPath("$.trackingNumber").value(parcel.trackingNumber()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currentBranch").isEmpty())
                .andExpect(jsonPath("$.from.city").value("Delgoda"))
                .andExpect(jsonPath("$.to.district").value("MATARA"))
                .andExpect(jsonPath("$.to.name").doesNotExist())
                .andExpect(jsonPath("$.myItems[0].assignmentId").value(pickup.id().toString()))
                .andExpect(jsonPath("$.myItems[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.myItems[0].outcome").value("PENDING"))
                // the sender, because the open item is a pickup
                .andExpect(jsonPath("$.contact.name").value("Delgoda Spice Traders"))
                .andExpect(jsonPath("$.contact.address").value("14 Kandy Road"))
                .andExpect(jsonPath("$.history[0].status").value("CREATED"))
                .andExpect(jsonPath("$.history[0].note").doesNotExist())
                .andExpect(jsonPath("$.history[0].actorRole").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.feeAmount").doesNotExist());
    }

    @Test
    void aParcelIHandedOverShowsNoContactButItsBranch() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA));
        AssignmentView done = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                item(parcel, ItemOutcome.HANDED_OVER)));
        AssignmentView transfer = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)));
        List<AssignmentItemView> items = List.of(transfer.items().getFirst(), done.items().getFirst());
        when(assignments.findItemsForCourierAndParcel(courier.id(), parcel.id())).thenReturn(items);
        when(delivery.history(parcel.id())).thenReturn(List.of(new ParcelStatusEntry(ParcelStatus.AT_BRANCH, null,
                null, "COURIER", Instant.now(), District.GAMPAHA)));

        mvc.perform(get(PARCELS + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.myItems.length()").value(2))
                .andExpect(jsonPath("$.myItems[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.myItems[1].outcome").value("HANDED_OVER"))
                // the open item is a transfer: no contact
                .andExpect(jsonPath("$.contact").isEmpty())
                .andExpect(jsonPath("$.history[0].branch").value("GAMPAHA"))
                .andExpect(jsonPath("$.history[0].failureReason").isEmpty());
    }

    @Test
    void aParcelThatWasNeverInMyTasksIs404() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(ParcelStatus.FAILED));
        when(assignments.findItemsForCourierAndParcel(eq(courier.id()), any())).thenReturn(List.of());

        mvc.perform(get(PARCELS + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(delivery, never()).history(any());
    }

    @Test
    void failureReasonIsShownInTheParcelHistory() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(ParcelStatus.FAILED));
        AssignmentView round = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(parcel, ItemOutcome.FAILED)));
        when(assignments.findItemsForCourierAndParcel(courier.id(), parcel.id()))
                .thenReturn(List.of(round.items().getFirst()));
        when(delivery.history(parcel.id())).thenReturn(List.of(new ParcelStatusEntry(ParcelStatus.FAILED,
                "Nobody home", FailureReason.RECIPIENT_UNAVAILABLE, "COURIER", Instant.now())));

        mvc.perform(get(PARCELS + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().isOk())
                // the delivery item is still open (FAILED until RETURNED): the recipient
                .andExpect(jsonPath("$.contact.name").value("Nimal Perera"))
                .andExpect(jsonPath("$.history[0].failureReason").value("RECIPIENT_UNAVAILABLE"));
    }
}
