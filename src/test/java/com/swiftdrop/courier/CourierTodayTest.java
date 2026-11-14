package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourierTodayTest extends CourierApiTest {

    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    @Test
    void asksDispatchForOpenTasksAndThoseClosedSinceSriLankanMidnight() throws Exception {
        Courier courier = registerCourier();
        Instant midnightBefore = startOfTodayInSriLanka();

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(LocalDate.now(SRI_LANKA).toString()))
                .andExpect(jsonPath("$.assignments").isEmpty())
                .andExpect(jsonPath("$.open").value(0))
                .andExpect(jsonPath("$.completedToday").value(0));

        Instant midnightAfter = startOfTodayInSriLanka();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(assignments).findOpenForCourier(eq(courier.id()), since.capture());
        // Two readings, in case the test runs across midnight.
        assertThat(since.getValue()).isIn(midnightBefore, midnightAfter);
        // the list needs no parcel data
        verify(delivery, never()).findAllById(any());
    }

    @Test
    void listsInProgressThenAssignedThenClosedWithCounts() throws Exception {
        Courier courier = registerCourier();
        Instant now = Instant.now();
        DeliverySnapshot a = parcel(ParcelStatus.CREATED);
        DeliverySnapshot b = parcel(ParcelStatus.PICKED_UP);
        DeliverySnapshot c = parcel(ParcelStatus.CREATED);

        AssignmentView laterAssigned = task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.ASSIGNED,
                at(now, 1), item(parcel(ParcelStatus.AT_BRANCH), ItemOutcome.PENDING));
        AssignmentView earlierAssigned = task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                at(now, 2), item(parcel(ParcelStatus.AT_BRANCH), ItemOutcome.PENDING));
        // 3 parcels: one collected (still held), one handed over, one not collected
        AssignmentView inProgress = task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                at(now, 3), item(a, ItemOutcome.COLLECTED), item(b, ItemOutcome.HANDED_OVER),
                item(c, ItemOutcome.NOT_COLLECTED));
        AssignmentView completedEarlier = task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                at(now, 6), item(parcel(ParcelStatus.AT_BRANCH), ItemOutcome.HANDED_OVER));
        AssignmentView cancelledLater = task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.CANCELLED,
                at(now, 4), item(parcel(ParcelStatus.AT_BRANCH), ItemOutcome.PENDING));
        AssignmentView someoneElses = task(UUID.randomUUID(), AssignmentType.PICKUP, AssignmentStatus.ASSIGNED,
                at(now, 9), item(parcel(ParcelStatus.CREATED), ItemOutcome.PENDING));
        when(assignments.findOpenForCourier(eq(courier.id()), any())).thenReturn(List.of(completedEarlier,
                laterAssigned, someoneElses, inProgress, cancelledLater, earlierAssigned));

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(3))
                .andExpect(jsonPath("$.completedToday").value(1))
                .andExpect(jsonPath("$.assignments.length()").value(5))
                .andExpect(jsonPath("$.assignments[0].assignmentId").value(inProgress.id().toString()))
                .andExpect(jsonPath("$.assignments[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.assignments[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignments[0].originBranch").isEmpty())
                .andExpect(jsonPath("$.assignments[0].destinationBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.assignments[0].note").value("Morning round"))
                .andExpect(jsonPath("$.assignments[0].counts.total").value(3))
                .andExpect(jsonPath("$.assignments[0].counts.open").value(1))
                .andExpect(jsonPath("$.assignments[0].counts.closed").value(2))
                .andExpect(jsonPath("$.assignments[0].counts.problems").value(1))
                .andExpect(jsonPath("$.assignments[0].canComplete").value(false))
                .andExpect(jsonPath("$.assignments[1].assignmentId").value(earlierAssigned.id().toString()))
                .andExpect(jsonPath("$.assignments[1].originBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.assignments[1].destinationBranch").value("MATARA"))
                .andExpect(jsonPath("$.assignments[2].assignmentId").value(laterAssigned.id().toString()))
                // closed: newest close first (the cancelled one closed later)
                .andExpect(jsonPath("$.assignments[3].assignmentId").value(cancelledLater.id().toString()))
                .andExpect(jsonPath("$.assignments[3].status").value("CANCELLED"))
                .andExpect(jsonPath("$.assignments[4].assignmentId").value(completedEarlier.id().toString()))
                .andExpect(jsonPath("$.assignments[4].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.assignments[4].canComplete").value(false))
                .andExpect(jsonPath("$.assignments[0].items").doesNotExist())
                .andExpect(jsonPath("$.assignments[0].courierId").doesNotExist())
                .andExpect(jsonPath("$.assignments[0].version").doesNotExist());
    }

    @Test
    void aTaskWithEveryParcelClosedCanBeCompleted() throws Exception {
        Courier courier = registerCourier();
        AssignmentView ready = task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(parcel(ParcelStatus.AT_BRANCH), ItemOutcome.HANDED_OVER),
                item(parcel(ParcelStatus.CREATED), ItemOutcome.NOT_COLLECTED));
        when(assignments.findOpenForCourier(eq(courier.id()), any())).thenReturn(List.of(ready));

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(jsonPath("$.assignments[0].canComplete").value(true))
                .andExpect(jsonPath("$.assignments[0].counts.open").value(0));
    }

    private static Instant at(Instant now, int hoursAgo) {
        return now.minus(hoursAgo, ChronoUnit.HOURS);
    }

    private static Instant startOfTodayInSriLanka() {
        return LocalDate.now(SRI_LANKA).atStartOfDay(SRI_LANKA).toInstant();
    }
}
