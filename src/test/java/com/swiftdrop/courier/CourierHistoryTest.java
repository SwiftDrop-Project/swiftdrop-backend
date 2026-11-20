package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /courier/deliveries/history}: the parcels the courier completed, pickups and deliveries, newest completion
 * first. A parcel is listed as soon as its own item closes, even while the rest of its task is still in progress;
 * parcels still active (collected, out for delivery, failed and not yet returned, waiting) are not.
 */
class CourierHistoryTest extends CourierApiTest {

    /** A: handed over, C: delivered, E: not taken out (all in open tasks), and an older delivered parcel. */
    private record Scenario(Courier courier, DeliverySnapshot handedOver, DeliverySnapshot delivered,
                            DeliverySnapshot notTakenOut, DeliverySnapshot older) {
    }

    private Scenario scenario() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot handedOver = parcel(courier.id(), ParcelStatus.AT_BRANCH);
        DeliverySnapshot collected = parcel(courier.id(), ParcelStatus.PICKED_UP);
        givenTask(courier.id(), AssignmentType.PICKUP,
                step(handedOver, ItemOutcome.HANDED_OVER), step(collected, ItemOutcome.COLLECTED));
        DeliverySnapshot delivered = parcel(courier.id(), ParcelStatus.DELIVERED);
        DeliverySnapshot failed = parcel(courier.id(), ParcelStatus.FAILED);
        DeliverySnapshot notTakenOut = parcel(courier.id(), ParcelStatus.AT_BRANCH);
        givenTask(courier.id(), AssignmentType.DELIVERY, step(delivered, ItemOutcome.DELIVERED),
                step(failed, ItemOutcome.FAILED), step(notTakenOut, ItemOutcome.NOT_LOADED));
        // An hour ago, in a task that completed then.
        DeliverySnapshot older = given(parcel(courier.id(), ParcelStatus.DELIVERED, Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600)));
        // Someone else's completed parcel.
        given(parcel(UUID.randomUUID(), ParcelStatus.DELIVERED));
        return new Scenario(courier, handedOver, delivered, notTakenOut, older);
    }

    @Test
    void listsCompletedPickupsAndDeliveriesNewestFirstEvenWhileTheirTaskIsInProgress() throws Exception {
        Scenario s = scenario();

        mvc.perform(get(BASE + "/history").cookie(s.courier().cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].parcelId").value(s.notTakenOut().id().toString()))
                .andExpect(jsonPath("$.content[0].task.outcome").value("NOT_LOADED"))
                .andExpect(jsonPath("$.content[0].status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.content[1].parcelId").value(s.handedOver().id().toString()))
                .andExpect(jsonPath("$.content[1].stop").value("PICKUP"))
                .andExpect(jsonPath("$.content[1].task.outcome").value("HANDED_OVER"))
                // its task still has a collected parcel to hand over
                .andExpect(jsonPath("$.content[1].task.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.content[1].completed").value(true))
                .andExpect(jsonPath("$.content[1].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].nextStatuses").isEmpty())
                .andExpect(jsonPath("$.content[1].pickup").isEmpty())
                .andExpect(jsonPath("$.content[1].recipient").isEmpty())
                .andExpect(jsonPath("$.content[1].from.city").value("Nugegoda"))
                .andExpect(jsonPath("$.content[1].to.district").value("KANDY"))
                .andExpect(jsonPath("$.content[2].parcelId").value(s.delivered().id().toString()))
                .andExpect(jsonPath("$.content[2].task.outcome").value("DELIVERED"))
                .andExpect(jsonPath("$.content[3].parcelId").value(s.older().id().toString()))
                .andExpect(jsonPath("$.content[3].task.status").value("COMPLETED"));

        // Every task of the courier, open or closed (dispatch has no item-level query).
        verify(assignments).findOpenForCourier(s.courier().id(), Instant.EPOCH);
    }

    @Test
    void pagesThroughTheCompletedParcels() throws Exception {
        Scenario s = scenario();

        mvc.perform(get(BASE + "/history").cookie(s.courier().cookie()).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].parcelId").value(s.delivered().id().toString()))
                .andExpect(jsonPath("$.content[1].parcelId").value(s.older().id().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(BASE + "/history").cookie(s.courier().cookie()).param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void aTrackingNumberShowsOnlyThatParcelsCompletedSteps() throws Exception {
        Scenario s = scenario();
        when(delivery.findByTrackingNumber(s.handedOver().trackingNumber()))
                .thenReturn(Optional.of(s.handedOver()));

        mvc.perform(get(BASE + "/history").cookie(s.courier().cookie())
                        .param("trackingNumber", " " + s.handedOver().trackingNumber().toLowerCase().substring(0, 6)
                                + " " + s.handedOver().trackingNumber().substring(6) + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].parcelId").value(s.handedOver().id().toString()));

        verify(assignments).findItemsForCourierAndParcel(s.courier().id(), s.handedOver().id());
        verify(assignments, never()).findOpenForCourier(any(), any());
    }

    @Test
    void aParcelStillActiveIsNotInTheHistory() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot failed = given(parcel(courier.id(), ParcelStatus.FAILED));
        when(delivery.findByTrackingNumber(failed.trackingNumber())).thenReturn(Optional.of(failed));

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("trackingNumber", failed.trackingNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE + "/history").cookie(courier.cookie()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anUnknownTrackingNumberIsAnEmptyPage() throws Exception {
        Courier courier = registerCourier();
        when(delivery.findByTrackingNumber(any())).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("trackingNumber", "SD2611999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(assignments, never()).findOpenForCourier(any(), any());
        verify(assignments, never()).findItemsForCourierAndParcel(any(), any());
    }

    @Test
    void badPagingIs400() throws Exception {
        Courier courier = registerCourier();

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").value("Use a page size from 1 to 100."));
        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.page").value("Use a page number of 0 or more."));
        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("size", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(assignments, never()).findOpenForCourier(eq(courier.id()), any());
    }
}
