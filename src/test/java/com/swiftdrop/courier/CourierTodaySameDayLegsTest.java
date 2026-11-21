package com.swiftdrop.courier;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (real delivery and dispatch modules): one courier picks a parcel up, hands it over at the branch, then
 * delivers the same parcel later the same day. Today shows the parcel once, as its latest finished step (the
 * delivery, not the earlier pickup handover); History keeps one entry per task.
 */
class CourierTodaySameDayLegsTest extends RealWorkflowTest {

    @Test
    void aParcelPickedUpAndDeliveredByTheSameCourierTodayShowsItsDeliveryInToday() throws Exception {
        assertRealDispatch();
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel("Delgoda", "GAMPAHA", "Gampaha", "GAMPAHA");

        UUID pickup = pickupTask(courier, "GAMPAHA", parcel);
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, parcel, "S. Fernando").andExpect(status().isOk());

        UUID delivery = deliveryTask(courier, "GAMPAHA", parcel);
        // While the delivery is open, the open step wins (unchanged behaviour).
        JsonNode open = todayCard(courier, parcel);
        assertThat(open.get("task").get("assignmentId").asString()).isEqualTo(delivery.toString());
        assertThat(open.get("completed").asBoolean()).isFalse();

        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(courier, parcel, "Kamala Silva").andExpect(status().isOk());

        // Both steps are finished today: Today shows the parcel once, as the delivery.
        assertThat(todayIds(courier)).containsOnlyOnce(parcel.toString());
        JsonNode today = read(today(courier).andExpect(status().isOk()));
        assertThat(today.get("done").asInt()).isEqualTo(1);
        JsonNode card = todayCard(courier, parcel);
        assertThat(card.get("task").get("assignmentId").asString()).isEqualTo(delivery.toString());
        assertThat(card.get("task").get("type").asString()).isEqualTo("DELIVERY");
        assertThat(card.get("task").get("outcome").asString()).isEqualTo("DELIVERED");
        assertThat(card.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(card.get("completed").asBoolean()).isTrue();
        assertThat(card.get("nextStatuses")).isEmpty();
        assertThat(card.get("proof").get("hasPhoto").asBoolean()).isTrue();

        // The parcel's detail page also shows its latest finished step.
        JsonNode detail = read(detail(courier, parcel).andExpect(status().isOk()));
        assertThat(detail.get("task").get("assignmentId").asString()).isEqualTo(delivery.toString());
        assertThat(detail.get("task").get("outcome").asString()).isEqualTo("DELIVERED");

        // History keeps both tasks, newest completion first.
        List<String> entries = new ArrayList<>();
        for (JsonNode entry : read(history(courier).andExpect(status().isOk())).get("content")) {
            if (entry.get("parcelId").asString().equals(parcel.toString())) {
                entries.add(entry.get("task").get("assignmentId").asString() + ":" + entry.get("task").get("outcome")
                        .asString());
            }
        }
        assertThat(entries).containsExactly(delivery + ":DELIVERED", pickup + ":HANDED_OVER");
    }
}
