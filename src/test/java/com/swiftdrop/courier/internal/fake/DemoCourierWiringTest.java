// TEMPORARY - delete with the courier fakes.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo end to end over HTTP, with the real delivery module on PostgreSQL (Testcontainers) and the fake dispatch:
 * the seeder creates real parcels at their starting status, the demo courier sees the Delgoda → Gampaha → Matara →
 * Deniyaya tasks and walks all three (PICKUP with one parcel not collected, TRANSFER, DELIVERY with one failed parcel
 * returned to the branch). The other courier's task stays invisible.
 */
@TestPropertySource(properties = {
        "swiftdrop.dev.fake-dispatch=true",
        "swiftdrop.seed.demo-users=true",
        "swiftdrop.seed.demo-password=" + DemoCourierWiringTest.DEMO_PASSWORD
})
class DemoCourierWiringTest extends IntegrationTest {

    static final String DEMO_PASSWORD = "Demo12345";
    private static final String TASKS = "/api/v1/courier/assignments";
    private static final String PARCELS = "/api/v1/courier/parcels";

    @Autowired
    DeliveryApi deliveries;

    @Autowired
    FakeAssignmentApi assignments;

    @Autowired
    DemoCourierSeeder seeder;

    private DemoCourierSeeder.DemoRun run;

    @BeforeEach
    void demoRun() {
        run = seeder.lastRun();
        assertThat(run).as("the seeder ran (Testcontainers counts as a local database)").isNotNull();
    }

    /** Before the walk: the walk moves these parcels on. */
    private void theSeededParcelsAreRealOrdersOfTheDemoMerchantAtTheirStartingStatus() {
        for (int number = 1; number <= 9; number++) {
            assertThat(deliveries.findById(run.parcelId(number))).get()
                    .satisfies(parcel -> assertThat(parcel.merchantOrderRef()).startsWith("WP9-DEMO-" + run.run()));
        }
        assertThat(deliveries.findById(run.parcelId(1)).orElseThrow().status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(deliveries.findById(run.parcelId(4)).orElseThrow().currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(deliveries.findById(run.parcelId(6)).orElseThrow().currentBranch()).isEqualTo(District.MATARA);
        assertThat(deliveries.findById(run.parcelId(9)).orElseThrow().status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(deliveries.history(run.parcelId(6))).extracting(entry -> entry.status()).containsExactly(
                ParcelStatus.AT_BRANCH, ParcelStatus.IN_TRANSIT, ParcelStatus.AT_BRANCH, ParcelStatus.PICKED_UP,
                ParcelStatus.CREATED);
    }

    @Test
    void demoCourierWalksThePickupTheTransferAndTheDelivery() throws Exception {
        theSeededParcelsAreRealOrdersOfTheDemoMerchantAtTheirStartingStatus();
        Cookie courier = tokenCookie(login(DemoCourierSeeder.DEMO_COURIER_EMAIL, DEMO_PASSWORD, false)
                .andExpect(status().isOk()).andReturn());

        JsonNode today = read(mvc.perform(get(TASKS + "/today").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(3))
                .andExpect(jsonPath("$.completedToday").value(0))
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.assignments[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.assignments[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignments[0].destinationBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.assignments[0].counts.total").value(3))
                .andExpect(jsonPath("$.assignments[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.assignments[2].type").value("DELIVERY")));
        String pickup = today.get("assignments").get(0).get("assignmentId").asString();
        String transfer = today.get("assignments").get(1).get("assignmentId").asString();
        String delivery = today.get("assignments").get(2).get("assignmentId").asString();

        walkThePickup(courier, pickup);
        walkTheTransfer(courier, transfer);
        walkTheDelivery(courier, delivery);

        mvc.perform(get(TASKS + "/today").cookie(courier))
                .andExpect(jsonPath("$.open").value(0))
                .andExpect(jsonPath("$.completedToday").value(3));
        mvc.perform(get(TASKS + "/history").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(TASKS + "/history").cookie(courier).param("trackingNumber", typedLoosely(run.parcel(7).trackingNumber())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DELIVERY"));

        theOtherCouriersTaskIsInvisible(courier);
    }

    private void walkThePickup(Cookie courier, String task) throws Exception {
        mvc.perform(get(TASKS + "/{id}", task).cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].trackingNumber").value(run.parcel(1).trackingNumber()))
                .andExpect(jsonPath("$.items[0].contact.name").value("Delgoda Spice Traders"))
                .andExpect(jsonPath("$.items[0].from.city").value("Delgoda"))
                .andExpect(jsonPath("$.items[0].to.city").value("Deniyaya"))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("COLLECTED"));

        outcome(task, 1, Map.of("outcome", "COLLECTED"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("PICKED_UP"));
        outcome(task, 2, Map.of("outcome", "COLLECTED", "note", "Two boxes taped together"), courier)
                .andExpect(status().isOk());
        outcome(task, 3, Map.of("outcome", "NOT_COLLECTED", "problem", "SENDER_UNAVAILABLE",
                "note", "Shop closed until 2 pm"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[2].outcome").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.items[2].parcelStatus").value("CREATED"))
                .andExpect(jsonPath("$.counts.problems").value(1));

        // the two collected parcels are still with the courier
        complete(task, courier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));
        handover(task, List.of(1, 2), "S. Silva", courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.items[0].parcelStatus").value("AT_BRANCH"))
                .andExpect(jsonPath("$.items[1].receivedBy").value("S. Silva"))
                .andExpect(jsonPath("$.canComplete").value(true));
        complete(task, courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // completed task, but the parcel is not delivered: it waits at the Gampaha branch
        mvc.perform(get(PARCELS + "/{id}", parcelId(1)).cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.currentBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.contact").isEmpty())
                .andExpect(jsonPath("$.myItems[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.myItems[0].outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.history[0].status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.history[0].branch").value("GAMPAHA"))
                .andExpect(jsonPath("$.history[1].status").value("PICKED_UP"));
        mvc.perform(get(PARCELS + "/{id}", parcelId(3)).cookie(courier))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.myItems[0].outcome").value("NOT_COLLECTED"));
        outcome(task, 3, Map.of("outcome", "COLLECTED"), courier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
    }

    private void walkTheTransfer(Cookie courier, String task) throws Exception {
        mvc.perform(get(TASKS + "/{id}", task).cookie(courier))
                .andExpect(jsonPath("$.originBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.destinationBranch").value("MATARA"))
                .andExpect(jsonPath("$.items[0].contact").isEmpty())
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("LOADED"));

        outcome(task, 4, Map.of("outcome", "LOADED"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("IN_TRANSIT"));
        outcome(task, 5, Map.of("outcome", "LOADED"), courier).andExpect(status().isOk());
        handover(task, List.of(4, 5), "R. Perera", courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canComplete").value(true));
        complete(task, courier).andExpect(status().isOk());

        mvc.perform(get(PARCELS + "/{id}", parcelId(4)).cookie(courier))
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.currentBranch").value("MATARA"))
                .andExpect(jsonPath("$.history[0].branch").value("MATARA"))
                .andExpect(jsonPath("$.history[1].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.history[2].branch").value("GAMPAHA"));
    }

    private void walkTheDelivery(Cookie courier, String task) throws Exception {
        mvc.perform(get(TASKS + "/{id}", task).cookie(courier))
                .andExpect(jsonPath("$.originBranch").value("MATARA"))
                .andExpect(jsonPath("$.items[0].trackingNumber").value(run.parcel(6).trackingNumber()))
                .andExpect(jsonPath("$.items[0].contact.name").value("Anoma Rathnayake"))
                .andExpect(jsonPath("$.items[0].codAmount").value(2450.00));

        for (int parcel : List.of(6, 7, 8)) {
            outcome(task, parcel, Map.of("outcome", "LOADED"), courier).andExpect(status().isOk());
        }
        outcome(task, 6, Map.of("outcome", "DELIVERED", "recipientName", "Anoma Rathnayake"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].parcelStatus").value("DELIVERED"));
        outcome(task, 7, Map.of("outcome", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE",
                "note", "No answer, called twice"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].parcelStatus").value("FAILED"))
                .andExpect(jsonPath("$.items[1].readyForHandover").value(true));
        outcome(task, 8, Map.of("outcome", "DELIVERED", "recipientName", "Chamari Peiris"), courier)
                .andExpect(status().isOk());

        // the failed parcel is still with the courier
        complete(task, courier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));
        handover(task, List.of(7), "K. Dias", courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].outcome").value("RETURNED"))
                .andExpect(jsonPath("$.items[1].parcelStatus").value("AT_BRANCH"))
                .andExpect(jsonPath("$.items[1].failureReason").value("RECIPIENT_UNAVAILABLE"))
                .andExpect(jsonPath("$.canComplete").value(true));
        complete(task, courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.problems").value(1));

        mvc.perform(get(PARCELS + "/{id}", parcelId(7)).cookie(courier))
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.currentBranch").value("MATARA"))
                .andExpect(jsonPath("$.history[0].branch").value("MATARA"))
                .andExpect(jsonPath("$.history[1].status").value("FAILED"))
                .andExpect(jsonPath("$.history[1].failureReason").value("RECIPIENT_UNAVAILABLE"));
        mvc.perform(get(PARCELS + "/{id}", parcelId(6)).cookie(courier))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    private void theOtherCouriersTaskIsInvisible(Cookie courier) throws Exception {
        UUID parcel = parcelId(9);
        AssignmentView others = assignments.all().stream()
                .filter(task -> task.items().stream().anyMatch(item -> item.parcelId().equals(parcel)))
                .findFirst().orElseThrow();

        mvc.perform(get(TASKS + "/{id}", others.id()).cookie(courier))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post(TASKS + "/{id}/items/{parcelId}/outcome", others.id(), parcel).cookie(courier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"DELIVERED\",\"recipientName\":\"Sunil Fernando\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(TASKS + "/{id}/complete", others.id()).cookie(courier))
                .andExpect(status().isNotFound());
        mvc.perform(get(PARCELS + "/{id}", parcel).cookie(courier))
                .andExpect(status().isNotFound());
    }

    // ---- Helpers ----

    /** Demo parcel {@code number} of this run. */
    private UUID parcelId(int number) {
        return run.parcelId(number);
    }

    /** "SD2611104827" → "sd2611 104827": lower case with a space, as a courier might type it. */
    private static String typedLoosely(String trackingNumber) {
        return trackingNumber.substring(0, 6).toLowerCase() + " " + trackingNumber.substring(6);
    }

    private ResultActions outcome(String task, int parcel, Map<String, ?> body, Cookie cookie) throws Exception {
        return mvc.perform(post(TASKS + "/{id}/items/{parcelId}/outcome", task, parcelId(parcel)).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions handover(String task, List<Integer> parcels, String receivedBy, Cookie cookie)
            throws Exception {
        List<UUID> ids = parcels.stream().map(this::parcelId).toList();
        return mvc.perform(post(TASKS + "/{id}/handover", task).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("parcelIds", ids, "receivedBy", receivedBy))));
    }

    private ResultActions complete(String task, Cookie cookie) throws Exception {
        return mvc.perform(post(TASKS + "/{id}/complete", task).cookie(cookie));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
