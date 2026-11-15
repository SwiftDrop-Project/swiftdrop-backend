package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.ItemOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

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
    void passesThePageAndTheParcelOfTheNormalisedTrackingNumber() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot delivered = given(parcel(courier.id(), ParcelStatus.DELIVERED));
        when(delivery.findByTrackingNumber("SD2611004817")).thenReturn(Optional.of(delivered));
        AssignmentItemView item = taskOf(delivered.id()).items().getFirst();
        when(assignments.findFinishedDeliveriesForCourier(eq(courier.id()), eq(delivered.id()), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(1, 5), 6));

        mvc.perform(get(BASE + "/history").cookie(courier.cookie())
                        .param("page", "1").param("size", "5").param("trackingNumber", " sd 2611 0048 17 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].parcelId").value(delivered.id().toString()))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].task.outcome").value(ItemOutcome.DELIVERED.name()))
                .andExpect(jsonPath("$.content[0].nextStatuses").isEmpty())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assignments).findFinishedDeliveriesForCourier(eq(courier.id()), eq(delivered.id()), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void anUnknownTrackingNumberIsAnEmptyPage() throws Exception {
        Courier courier = registerCourier();
        when(delivery.findByTrackingNumber(any())).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()).param("trackingNumber", "SD2611999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(assignments, never()).findFinishedDeliveriesForCourier(any(), any(), any());
    }

    @Test
    void defaultsToTheFirstPageOf20WithoutAFilter() throws Exception {
        Courier courier = registerCourier();
        when(assignments.findFinishedDeliveriesForCourier(eq(courier.id()), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get(BASE + "/history").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assignments).findFinishedDeliveriesForCourier(eq(courier.id()), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
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

        verify(assignments, never()).findFinishedDeliveriesForCourier(any(), any(), any());
    }
}
