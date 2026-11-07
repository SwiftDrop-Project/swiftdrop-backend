package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourierTodayTest extends CourierApiTest {

    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    @Test
    void asksDeliveryForOpenParcelsAndThoseFinishedSinceSriLankanMidnight() throws Exception {
        Courier courier = registerCourier();
        Instant midnightBefore = startOfTodayInSriLanka();

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.toDo").value(0))
                .andExpect(jsonPath("$.done").value(0));

        Instant midnightAfter = startOfTodayInSriLanka();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(delivery).findForCourier(eq(courier.id()), since.capture());
        // Two readings, in case the test runs across midnight.
        assertThat(since.getValue()).isIn(midnightBefore, midnightAfter);
    }

    @Test
    void listsPickupsThenDeliveriesThenFinishedWithCounts() throws Exception {
        Courier courier = registerCourier();
        Instant now = Instant.now();
        DeliverySnapshot laterPickup = parcel(courier.id(), ParcelStatus.CREATED, at(now, 2), at(now, 2));
        DeliverySnapshot earlierPickup = parcel(courier.id(), ParcelStatus.CREATED, at(now, 4), at(now, 4));
        DeliverySnapshot onTheWay = parcel(courier.id(), ParcelStatus.OUT_FOR_DELIVERY, at(now, 3), at(now, 1));
        DeliverySnapshot deliveredEarlier = parcel(courier.id(), ParcelStatus.DELIVERED, at(now, 6), now.minusSeconds(40));
        DeliverySnapshot failedLater = parcel(courier.id(), ParcelStatus.FAILED, at(now, 5), now.minusSeconds(20));
        DeliverySnapshot someoneElses = parcel(UUID.randomUUID(), ParcelStatus.CREATED, at(now, 9), at(now, 9));
        when(delivery.findForCourier(eq(courier.id()), any())).thenReturn(
                List.of(deliveredEarlier, laterPickup, someoneElses, onTheWay, failedLater, earlierPickup));

        mvc.perform(get(BASE + "/today").cookie(courier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(LocalDate.now(SRI_LANKA).toString()))
                .andExpect(jsonPath("$.toDo").value(3))
                .andExpect(jsonPath("$.done").value(2))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].parcelId").value(earlierPickup.id().toString()))
                .andExpect(jsonPath("$.items[0].stop").value("PICKUP"))
                .andExpect(jsonPath("$.items[0].nextStatuses[0]").value("PICKED_UP"))
                .andExpect(jsonPath("$.items[1].parcelId").value(laterPickup.id().toString()))
                .andExpect(jsonPath("$.items[2].parcelId").value(onTheWay.id().toString()))
                .andExpect(jsonPath("$.items[2].stop").value("DELIVERY"))
                .andExpect(jsonPath("$.items[2].nextStatuses[0]").value("DELIVERED"))
                .andExpect(jsonPath("$.items[2].nextStatuses[1]").value("FAILED"))
                .andExpect(jsonPath("$.items[3].parcelId").value(failedLater.id().toString()))
                .andExpect(jsonPath("$.items[3].nextStatuses").isEmpty())
                .andExpect(jsonPath("$.items[4].parcelId").value(deliveredEarlier.id().toString()))
                .andExpect(jsonPath("$.items[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.items[0].merchantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].merchantOrderRef").doesNotExist())
                .andExpect(jsonPath("$.items[0].feeAmount").doesNotExist())
                .andExpect(jsonPath("$.items[0].version").doesNotExist());
    }

    private static Instant at(Instant now, int hoursAgo) {
        return now.minus(hoursAgo, ChronoUnit.HOURS);
    }

    private static Instant startOfTodayInSriLanka() {
        return LocalDate.now(SRI_LANKA).atStartOfDay(SRI_LANKA).toInstant();
    }
}
