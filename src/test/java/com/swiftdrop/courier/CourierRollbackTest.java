package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The parcel status and the task outcome are written in one transaction: when dispatch fails after delivery already
 * changed the parcel, nothing is kept. Runs a real transaction against both (TEMPORARY) fakes, which undo their
 * in-memory changes on rollback like the real tables; a Mockito spy makes dispatch fail.
 */
@TestPropertySource(properties = {
        "swiftdrop.dev.fake-delivery=true",
        "swiftdrop.dev.fake-dispatch=true",
        "swiftdrop.seed.demo-users=true",
        "swiftdrop.seed.demo-password=" + CourierRollbackTest.DEMO_PASSWORD
})
class CourierRollbackTest extends IntegrationTest {

    static final String DEMO_PASSWORD = "Demo12345";
    private static final String TASKS = "/api/v1/courier/assignments";

    @MockitoSpyBean
    AssignmentApi assignments;

    @Autowired
    DeliveryApi deliveries;

    private Cookie courier;
    private UUID courierId;

    @BeforeEach
    void logIn() throws Exception {
        courier = tokenCookie(login("courier@swiftdrop.lk", DEMO_PASSWORD, false)
                .andExpect(status().isOk()).andReturn());
        courierId = UUID.fromString(read(mvc.perform(get("/api/v1/auth/me").cookie(courier))).get("id").asString());
    }

    @Test
    void whenDispatchFailsTheParcelKeepsItsStatusAndHistory() throws Exception {
        AssignmentView pickup = task("PICKUP");
        DeliverySnapshot parcel = parcel("SD2611060001");
        List<ParcelStatusEntry> history = deliveries.history(parcel.id());
        doThrow(new IllegalStateException("dispatch is down")).when(assignments).recordOutcome(any());

        outcome(pickup, parcel, Map.of("outcome", "COLLECTED"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(deliveries.findById(parcel.id())).contains(parcel);
        assertThat(deliveries.history(parcel.id())).isEqualTo(history);
        AssignmentView after = assignments.findByIdForCourier(pickup.id(), courierId).orElseThrow();
        assertThat(after.status()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(after.items().getFirst().outcome()).isEqualTo(ItemOutcome.PENDING);
    }

    @Test
    void aHandoverThatFailsHalfwayKeepsNothing() throws Exception {
        AssignmentView transfer = task("TRANSFER");
        DeliverySnapshot first = parcel("SD2611060004");
        DeliverySnapshot second = parcel("SD2611060005");
        outcome(transfer, first, Map.of("outcome", "LOADED")).andExpect(status().isOk());
        outcome(transfer, second, Map.of("outcome", "LOADED")).andExpect(status().isOk());
        AssignmentView loaded = assignments.findByIdForCourier(transfer.id(), courierId).orElseThrow();
        // the first parcel is handed over, then dispatch fails on the second
        doCallRealMethod().doThrow(new IllegalStateException("dispatch is down"))
                .when(assignments).recordOutcome(any());

        mvc.perform(post(TASKS + "/{id}/handover", transfer.id()).cookie(courier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("parcelIds", List.of(first.id(), second.id()),
                                "receivedBy", "R. Perera"))))
                .andExpect(status().isInternalServerError());

        assertThat(deliveries.findById(first.id()).orElseThrow().status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(deliveries.findById(first.id()).orElseThrow().currentBranch()).isNull();
        assertThat(deliveries.findById(second.id()).orElseThrow().status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(assignments.findByIdForCourier(transfer.id(), courierId)).contains(loaded);
    }

    private AssignmentView task(String type) throws Exception {
        JsonNode today = read(mvc.perform(get(TASKS + "/today").cookie(courier)).andExpect(status().isOk()));
        for (JsonNode task : today.get("assignments")) {
            if (type.equals(task.get("type").asString())) {
                UUID id = UUID.fromString(task.get("assignmentId").asString());
                return assignments.findByIdForCourier(id, courierId).orElseThrow();
            }
        }
        throw new AssertionError("No demo task of type " + type);
    }

    private DeliverySnapshot parcel(String trackingNumber) {
        return deliveries.findByTrackingNumber(trackingNumber).orElseThrow();
    }

    private ResultActions outcome(AssignmentView task, DeliverySnapshot parcel, Map<String, ?> body)
            throws Exception {
        return mvc.perform(post(TASKS + "/{id}/items/{parcelId}/outcome", task.id(), parcel.id()).cookie(courier)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
