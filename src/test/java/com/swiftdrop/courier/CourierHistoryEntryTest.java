package com.swiftdrop.courier;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /courier/deliveries/{parcelId}/history/{assignmentId} (real delivery and dispatch modules): one finished entry
 * of the courier's history exactly as it was, read-only, with the parcel's history cut off when it finished; and the
 * task-scoped proof of delivery (only the delivery that delivered the parcel carries it) on every courier response.
 */
class CourierHistoryEntryTest extends RealWorkflowTest {

    private ResultActions entry(StaffCourier courier, UUID parcelId, UUID assignmentId) throws Exception {
        return mvc.perform(get(COURIER + "/{parcelId}/history/{assignmentId}", parcelId, assignmentId)
                .cookie(courier.cookie()));
    }

    private static List<String> statuses(JsonNode detail) {
        List<String> statuses = new ArrayList<>();
        for (JsonNode event : detail.get("history")) {
            statuses.add(event.get("status").asString());
        }
        return statuses;
    }

    private static JsonNode historyItem(JsonNode page, UUID assignmentId) {
        for (JsonNode item : page.get("content")) {
            if (item.get("task").get("assignmentId").asString().equals(assignmentId.toString())) {
                return item;
            }
        }
        throw new AssertionError("no history entry for task " + assignmentId);
    }

    @Test
    void eachHistoryEntryOfOneParcelOpensItsOwnTaskReadOnlyWithHistoryUpToItsEnd() throws Exception {
        assertRealDispatch();
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID pickup = pickupTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());
        UUID delivery = deliveryTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(courier, parcel, "Kamala Silva").andExpect(status().isOk());

        // The pickup entry: that task, its outcome and handover, nothing from the later delivery.
        JsonNode picked = read(entry(courier, parcel, pickup).andExpect(status().isOk()));
        assertThat(picked.get("task").get("assignmentId").asString()).isEqualTo(pickup.toString());
        assertThat(picked.get("task").get("type").asString()).isEqualTo("PICKUP");
        assertThat(picked.get("task").get("outcome").asString()).isEqualTo("HANDED_OVER");
        assertThat(picked.get("task").get("receivedBy").asString()).isEqualTo("S. Fernando");
        assertThat(picked.get("task").get("branch").asString()).isEqualTo("GAMPAHA");
        assertThat(picked.get("completed").asBoolean()).isTrue();
        assertThat(picked.get("proof").isNull()).as("a pickup entry never carries delivery proof").isTrue();
        assertThat(picked.get("failureReason").isNull()).isTrue();
        assertThat(statuses(picked)).as("history ends when the pickup finished")
                .containsExactly("AT_BRANCH", "PICKED_UP", "CREATED");
        // Read-only, no contacts. The parcel's own status is still its current one (labelled apart on screen).
        assertThat(picked.get("nextStatuses")).isEmpty();
        assertThat(picked.get("canMarkUnableToCollect").asBoolean()).isFalse();
        assertThat(picked.get("pickup").isNull()).isTrue();
        assertThat(picked.get("recipient").isNull()).isTrue();
        assertThat(picked.get("status").asString()).isEqualTo("DELIVERED");

        // The delivery entry: its own task, the proof, the whole history up to the delivery.
        JsonNode delivered = read(entry(courier, parcel, delivery).andExpect(status().isOk()));
        assertThat(delivered.get("task").get("assignmentId").asString()).isEqualTo(delivery.toString());
        assertThat(delivered.get("task").get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("proof").get("recipientName").asString()).isEqualTo("Kamala Silva");
        assertThat(delivered.get("proof").get("hasPhoto").asBoolean()).isTrue();
        assertThat(statuses(delivered)).containsExactly("DELIVERED", "OUT_FOR_DELIVERY", "AT_BRANCH", "PICKED_UP",
                "CREATED");
        assertThat(delivered.get("nextStatuses")).isEmpty();

        // History list: proof only on the delivery entry. The live detail and Today are unchanged.
        JsonNode page = read(history(courier).andExpect(status().isOk()));
        assertThat(historyItem(page, pickup).get("proof").isNull()).isTrue();
        assertThat(historyItem(page, delivery).get("proof").get("hasPhoto").asBoolean()).isTrue();
        JsonNode live = read(detail(courier, parcel).andExpect(status().isOk()));
        assertThat(live.get("task").get("assignmentId").asString()).isEqualTo(delivery.toString());
        assertThat(statuses(live)).hasSize(5);
        assertThat(todayCard(courier, parcel).get("task").get("assignmentId").asString())
                .isEqualTo(delivery.toString());
    }

    @Test
    void openForeignMismatchedAndUnknownEntriesAreNotFound() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        StaffCourier other = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID otherParcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID pickup = pickupTask(courier, "GAMPAHA", parcel);

        // An open step is live work, never a history entry.
        entry(courier, parcel, pickup).andExpect(status().isNotFound());

        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());
        entry(courier, parcel, pickup).andExpect(status().isOk());

        // Another courier, a parcel that isn't in the task, an unknown task: the same 404, with no parcel data.
        String foreign = entry(other, parcel, pickup).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(foreign).doesNotContain(parcel.toString()).doesNotContain("Delgoda").doesNotContain("S. Fernando");
        entry(courier, otherParcel, pickup).andExpect(status().isNotFound());
        entry(courier, parcel, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get(COURIER + "/{parcelId}/history/not-a-uuid", parcel).cookie(courier.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(COURIER + "/{parcelId}/history/{assignmentId}", parcel, pickup))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(COURIER + "/{parcelId}/history/{assignmentId}", parcel, pickup).cookie(customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void aParcelFinishedInATaskWhoseSiblingsAreStillActiveCanBeOpened() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID first = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID second = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID task = pickupTask(courier, "GAMPAHA", first, second);
        moveTo(courier, first, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, first, "S. Fernando").andExpect(status().isOk());
        assertThat(taskStatusRow(task)).isEqualTo("IN_PROGRESS");

        JsonNode done = read(entry(courier, first, task).andExpect(status().isOk()));
        assertThat(done.get("task").get("outcome").asString()).isEqualTo("HANDED_OVER");
        assertThat(done.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(done.get("nextStatuses")).isEmpty();
        // The sibling is still open: not a history entry yet.
        entry(courier, second, task).andExpect(status().isNotFound());
    }

    @Test
    void aCourierWhoOnlyPickedUpNeverSeesTheDeliveryProofOrItsImage() throws Exception {
        StaffCourier collector = createCourier("GAMPAHA");
        StaffCourier deliverer = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        UUID pickup = pickupTask(collector, "GAMPAHA", parcel);
        moveTo(collector, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(collector, parcel, "S. Fernando").andExpect(status().isOk());
        deliveryTask(deliverer, "GAMPAHA", parcel);
        moveTo(deliverer, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(deliverer, parcel, "Kamala Silva").andExpect(status().isOk());

        // The collector: no proof metadata anywhere, and the image is refused.
        assertThat(historyItem(read(history(collector).andExpect(status().isOk())), pickup).get("proof").isNull())
                .isTrue();
        assertThat(read(detail(collector, parcel).andExpect(status().isOk())).get("proof").isNull()).isTrue();
        assertThat(todayCard(collector, parcel).get("proof").isNull()).isTrue();
        JsonNode entry = read(entry(collector, parcel, pickup).andExpect(status().isOk()));
        assertThat(entry.get("proof").isNull()).isTrue();
        assertThat(entry.toString()).doesNotContain("Kamala Silva");
        assertThat(statuses(entry)).doesNotContain("DELIVERED", "OUT_FOR_DELIVERY");
        mvc.perform(get(COURIER + "/{id}/proof", parcel).cookie(collector.cookie())).andExpect(status().isNotFound());

        // The courier who delivered it sees the proof and the image.
        assertThat(todayCard(deliverer, parcel).get("proof").get("recipientName").asString()).isEqualTo("Kamala Silva");
        mvc.perform(get(COURIER + "/{id}/proof", parcel).cookie(deliverer.cookie())).andExpect(status().isOk());
    }

    @Test
    void aReturnedDeliveryEntryHasNoProofAndShowsTheFailureInItsOwnHistory() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");
        pickupTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());
        UUID delivery = deliveryTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        changeStatus(courier, parcel, Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE"))
                .andExpect(status().isOk());
        // While it waits to be returned, the live detail says why it failed.
        assertThat(read(detail(courier, parcel).andExpect(status().isOk())).get("failureReason").asString())
                .isEqualTo("RECIPIENT_UNAVAILABLE");
        handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());

        JsonNode returned = read(entry(courier, parcel, delivery).andExpect(status().isOk()));
        assertThat(returned.get("task").get("outcome").asString()).isEqualTo("RETURNED");
        assertThat(returned.get("proof").isNull()).isTrue();
        assertThat(returned.get("failureReason").isNull()).as("the parcel isn't FAILED any more").isTrue();
        assertThat(statuses(returned)).startsWith("AT_BRANCH", "FAILED", "OUT_FOR_DELIVERY");
        assertThat(returned.get("history").get(1).get("failureReason").asString()).isEqualTo("RECIPIENT_UNAVAILABLE");
        assertThat(returned.get("nextStatuses")).isEmpty();
    }
}
