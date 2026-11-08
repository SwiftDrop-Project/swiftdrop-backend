// TEMPORARY - delete when WP3 implements DeliveryApi.
package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SWIFTDROP_FAKE_DELIVERY=true end to end: the app starts with the in-memory fake, the demo courier sees the seeded
 * parcels and can walk them through their statuses.
 */
@TestPropertySource(properties = {
        "swiftdrop.dev.fake-delivery=true",
        "swiftdrop.seed.demo-users=true",
        "swiftdrop.seed.demo-password=" + FakeDeliveryWiringTest.DEMO_PASSWORD
})
class FakeDeliveryWiringTest extends IntegrationTest {

    static final String DEMO_PASSWORD = "Demo12345";
    private static final String BASE = "/api/v1/courier/deliveries";

    @Test
    void demoCourierWalksASeededParcelToDelivered() throws Exception {
        Cookie courier = tokenCookie(login("courier@swiftdrop.lk", DEMO_PASSWORD, false)
                .andExpect(status().isOk()).andReturn());

        // FAILED is not finished any more (it goes back to a branch), so yesterday's failure is still to do
        JsonNode today = read(mvc.perform(get(BASE + "/today").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo").value(6))
                .andExpect(jsonPath("$.done").value(1))
                .andExpect(jsonPath("$.items.length()").value(7))
                .andExpect(jsonPath("$.items[0].stop").value("PICKUP"))
                .andExpect(jsonPath("$.items[1].stop").value("PICKUP"))
                .andExpect(jsonPath("$.items[6].status").value("DELIVERED")));

        String outForDelivery = idWithStatus(today, "OUT_FOR_DELIVERY");
        postStatus(outForDelivery, Map.of("status", "DELIVERED", "recipientName", "Kasun Wickramasinghe"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.proof.recipientName").value("Kasun Wickramasinghe"))
                .andExpect(jsonPath("$.history[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.history.length()").value(5));

        // Pressing the button twice: the second change is no longer allowed.
        postStatus(outForDelivery, Map.of("status", "DELIVERED", "recipientName", "Kasun Wickramasinghe"), courier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // A transfer now ends at a branch; this endpoint can't send one (assignments replace it in PR (c)).
        String inTransit = idWithStatus(today, "IN_TRANSIT");
        postStatus(inTransit, Map.of("status", "OUT_FOR_DELIVERY"), courier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        mvc.perform(get(BASE + "/today").cookie(courier))
                .andExpect(jsonPath("$.toDo").value(5))
                .andExpect(jsonPath("$.done").value(2));
        mvc.perform(get(BASE + "/history").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions postStatus(String parcelId, Map<String, ?> body,
                                                                          Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/status", parcelId).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String idWithStatus(JsonNode today, String status) {
        for (JsonNode item : today.get("items")) {
            if (status.equals(item.get("status").asString())) {
                return item.get("parcelId").asString();
            }
        }
        throw new AssertionError("No seeded parcel with status " + status);
    }
}
