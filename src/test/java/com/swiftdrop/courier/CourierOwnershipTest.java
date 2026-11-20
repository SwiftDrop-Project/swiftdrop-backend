package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A courier only reaches parcels in their own dispatch tasks; everything else looks like it doesn't exist (404). They
 * get only the contact their step needs (sender for a pickup, recipient for a delivery), while it is open.
 */
class CourierOwnershipTest extends CourierApiTest {

    @Test
    void ownParcelIsShownWithItsDetails() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parcelId").value(parcel.id().toString()))
                .andExpect(jsonPath("$.trackingNumber").value(parcel.trackingNumber()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.stop").value("PICKUP"))
                .andExpect(jsonPath("$.pickup.name").value("Lanka Crafts"))
                .andExpect(jsonPath("$.pickup.phone").value("+94760000101"))
                // a pickup courier gets the sender only; the recipient's place without personal data
                .andExpect(jsonPath("$.recipient").isEmpty())
                .andExpect(jsonPath("$.to.city").value("Kandy"))
                .andExpect(jsonPath("$.to.district").value("KANDY"))
                .andExpect(jsonPath("$.from.city").value("Nugegoda"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.canMarkUnableToCollect").value(true))
                .andExpect(jsonPath("$.codAmount").value(2450.00))
                .andExpect(jsonPath("$.nextStatuses[0]").value("PICKED_UP"))
                .andExpect(jsonPath("$.history").isArray())
                .andExpect(jsonPath("$.proof").isEmpty())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.merchantId").doesNotExist())
                .andExpect(jsonPath("$.feeAmount").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void anotherCouriersParcelIs404() throws Exception {
        Courier owner = registerCourier();
        Courier other = registerCourier();
        DeliverySnapshot parcel = given(parcel(owner.id(), ParcelStatus.CREATED));

        mvc.perform(get(BASE + "/{id}", parcel.id()).cookie(other.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postStatus(parcel.id(), Map.of("status", "PICKED_UP"), other.cookie())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postUnableToCollect(parcel.id(), Map.of("problem", "SENDER_UNAVAILABLE"), other.cookie())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(delivery, never()).history(any());
        verify(delivery, never()).changeStatus(any());
        verify(assignments, never()).recordOutcome(any());
    }

    @Test
    void anotherCouriersParcelIsNeitherOnTheirListNorInTheirHistory() throws Exception {
        Courier owner = registerCourier();
        Courier other = registerCourier();
        given(parcel(owner.id(), ParcelStatus.CREATED));
        given(parcel(owner.id(), ParcelStatus.DELIVERED));

        mvc.perform(get(BASE + "/today").cookie(other.cookie()))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(BASE + "/history").cookie(other.cookie()))
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void unknownParcelIs404() throws Exception {
        Courier courier = registerCourier();
        UUID unknown = UUID.randomUUID();

        mvc.perform(get(BASE + "/{id}", unknown).cookie(courier.cookie()))
                .andExpect(status().isNotFound());
        postStatus(unknown, Map.of("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isNotFound());
        postUnableToCollect(unknown, Map.of("problem", "SENDER_UNAVAILABLE"), courier.cookie())
                .andExpect(status().isNotFound());
        verify(delivery, never()).changeStatus(any());
    }

    @Test
    void parcelReassignedDuringTheChangeIs404() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(courier.id(), ParcelStatus.CREATED));
        when(delivery.changeStatus(any())).thenThrow(new ForbiddenException(ErrorCode.FORBIDDEN));

        postStatus(parcel.id(), Map.of("status", "PICKED_UP"), courier.cookie())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
