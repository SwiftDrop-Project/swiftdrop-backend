package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourierHistoryTest extends CourierApiTest {

    @Test
    void aTrackingNumberIsResolvedToTheParcelThroughDelivery() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = given(parcel(ParcelStatus.DELIVERED));
        AssignmentView done = task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.COMPLETED,
                item(parcel, ItemOutcome.DELIVERED));
        when(assignments.findClosedForCourier(eq(courier.id()), eq(parcel.id()), any()))
                .thenReturn(new PageImpl<>(List.of(done), PageRequest.of(1, 5), 6));
        String spaced = " " + parcel.trackingNumber().substring(0, 6).toLowerCase() + " "
                + parcel.trackingNumber().substring(6) + " ";

        mvc.perform(get(BASE + "/history").cookie(courier.cookie())
                        .param("page", "1").param("size", "5").param("trackingNumber", spaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].assignmentId").value(done.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].counts.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));

        verify(delivery).findByTrackingNumber(parcel.trackingNumber());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assignments).findClosedForCourier(eq(courier.id()), eq(parcel.id()), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void anUnknownTrackingNumberGivesAnEmptyPage() throws Exception {
        Courier courier = registerCourier();

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("trackingNumber", "SD2611999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(assignments, never()).findClosedForCourier(any(), any(), any());
    }

    @Test
    void defaultsToTheFirstPageOf20WithoutAFilter() throws Exception {
        Courier courier = registerCourier();
        when(assignments.findClosedForCourier(eq(courier.id()), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("trackingNumber", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assignments).findClosedForCourier(eq(courier.id()), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        verify(delivery, never()).findByTrackingNumber(any());
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

        verify(assignments, never()).findClosedForCourier(any(), any(), any());
    }
}
