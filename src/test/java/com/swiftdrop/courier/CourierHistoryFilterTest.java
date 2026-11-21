package com.swiftdrop.courier;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /courier/deliveries/history with type, outcome and completion-day filters (real delivery and dispatch modules):
 * applied to all of the courier's entries before paging, together with the tracking-number search, in Sri Lankan days.
 */
class CourierHistoryFilterTest extends RealWorkflowTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    private JsonNode historyPage(StaffCourier courier, Map<String, String> query) throws Exception {
        var request = get(COURIER + "/history").cookie(courier.cookie());
        for (var entry : query.entrySet()) {
            request = request.param(entry.getKey(), entry.getValue());
        }
        return read(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> entries(JsonNode page) {
        List<String> entries = new ArrayList<>();
        for (JsonNode item : page.get("content")) {
            entries.add(item.get("task").get("type").asString() + ":" + item.get("task").get("outcome").asString());
        }
        return entries;
    }

    @Test
    void filtersCombineWithEachOtherAndTheSearchAndArePagedAfterFiltering() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        // A: picked up and delivered. B: Unable to Collect at pickup. C: delivery failed and returned.
        UUID a = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID b = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID c = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        pickupTask(courier, "GAMPAHA", a, c);
        for (UUID parcel : List.of(a, c)) {
            moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
            handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());
        }
        pickupTask(courier, "GAMPAHA", b);
        unableToCollect(courier, b, "SENDER_UNAVAILABLE").andExpect(status().isOk());
        deliveryTask(courier, "GAMPAHA", a);
        moveTo(courier, a, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(courier, a, "Kamala Silva").andExpect(status().isOk());
        deliveryTask(courier, "GAMPAHA", c);
        moveTo(courier, c, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        changeStatus(courier, c, Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE"))
                .andExpect(status().isOk());
        handOver(courier, c, "S. Fernando").andExpect(status().isOk());

        JsonNode all = historyPage(courier, Map.of());
        assertThat(all.get("totalElements").asInt()).isEqualTo(5);

        assertThat(entries(historyPage(courier, Map.of("type", "PICKUP"))))
                .containsExactlyInAnyOrder("PICKUP:HANDED_OVER", "PICKUP:HANDED_OVER", "PICKUP:NOT_COLLECTED");
        assertThat(entries(historyPage(courier, Map.of("type", "delivery"))))
                .containsExactlyInAnyOrder("DELIVERY:DELIVERED", "DELIVERY:RETURNED");
        JsonNode delivered = historyPage(courier, Map.of("outcome", "DELIVERED"));
        assertThat(entries(delivered)).containsExactly("DELIVERY:DELIVERED");
        assertThat(delivered.get("content").get(0).get("proof").get("hasPhoto").asBoolean()).isTrue();
        assertThat(entries(historyPage(courier, Map.of("type", "DELIVERY", "outcome", "RETURNED"))))
                .containsExactly("DELIVERY:RETURNED");
        assertThat(historyPage(courier, Map.of("type", "PICKUP", "outcome", "DELIVERED")).get("totalElements").asInt())
                .isZero();

        // With the tracking-number search.
        String trackingA = parcel(a).trackingNumber();
        assertThat(entries(historyPage(courier, Map.of("trackingNumber", trackingA, "type", "PICKUP"))))
                .containsExactly("PICKUP:HANDED_OVER");
        // Proof stays task-scoped in filtered results.
        assertThat(historyPage(courier, Map.of("trackingNumber", trackingA, "type", "PICKUP"))
                .get("content").get(0).get("proof").isNull()).isTrue();

        // Paged after filtering: totals count only matching entries.
        JsonNode firstPickup = historyPage(courier, Map.of("type", "PICKUP", "size", "2", "page", "0"));
        assertThat(firstPickup.get("totalElements").asInt()).isEqualTo(3);
        assertThat(firstPickup.get("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPickup.get("content")).hasSize(2);
        assertThat(historyPage(courier, Map.of("type", "PICKUP", "size", "2", "page", "1")).get("content")).hasSize(1);

        // Completion days in Sri Lankan time (from the entries' own completion times, both days included).
        LocalDate day = Instant.parse(all.get("content").get(0).get("completedAt").asString()).atZone(COLOMBO)
                .toLocalDate();
        assertThat(historyPage(courier, Map.of("completedFrom", day.toString(), "completedTo", day.toString()))
                .get("totalElements").asInt()).isEqualTo(5);
        assertThat(historyPage(courier, Map.of("completedTo", day.minusDays(1).toString())).get("totalElements").asInt())
                .isZero();
        assertThat(historyPage(courier, Map.of("completedFrom", day.plusDays(1).toString())).get("totalElements").asInt())
                .isZero();
        assertThat(historyPage(courier, Map.of("completedFrom", day.minusDays(29).toString(), "outcome", "NOT_COLLECTED"))
                .get("totalElements").asInt()).isEqualTo(1);

        // Another courier's entries never appear.
        StaffCourier other = createCourier("GAMPAHA");
        assertThat(historyPage(other, Map.of("type", "PICKUP")).get("totalElements").asInt()).isZero();
    }

    @Test
    void badFilterValuesAreFieldErrors() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        mvc.perform(get(COURIER + "/history").param("type", "TRANSFER").cookie(courier.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.type").exists());
        mvc.perform(get(COURIER + "/history").param("outcome", "PENDING").cookie(courier.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.outcome").exists());
        mvc.perform(get(COURIER + "/history").param("completedFrom", "21-11-2026").cookie(courier.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.completedFrom").exists());
        mvc.perform(get(COURIER + "/history").param("completedFrom", "2026-11-21").param("completedTo", "2026-11-20")
                        .cookie(courier.cookie()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.completedTo").exists());
        // Blank values mean no filter.
        mvc.perform(get(COURIER + "/history").param("type", "").param("outcome", " ").cookie(courier.cookie()))
                .andExpect(status().isOk());
    }
}
