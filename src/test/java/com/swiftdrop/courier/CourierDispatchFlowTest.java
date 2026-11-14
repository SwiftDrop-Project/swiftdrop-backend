package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real delivery, dispatch and courier modules together: what the dispatcher assigns shows on the courier's
 * screen, and the courier's status changes move the dispatch task along until it completes.
 */
class CourierDispatchFlowTest extends IntegrationTest {

    private static final String COURIER_BASE = "/api/v1/courier/deliveries";

    @Test
    void anAssignedParcelShowsForItsCourierAndTheirStepsCompleteTheTask() throws Exception {
        Cookie admin = loginAsAdmin();
        Cookie dispatcher = staffAfterFirstLogin("/api/v1/admin/dispatchers", Map.of("branch", "COLOMBO"), admin);
        String courierEmail = uniqueEmail();
        String courierId = createCourier(admin, courierEmail);
        Cookie courier = firstLogin(courierEmail);
        Cookie otherCourier = firstLogin(createdCourierEmail(admin));
        String parcelId = bookParcel(registerCustomer(uniqueEmail(), uniquePhone()));

        // Nothing assigned yet
        mvc.perform(get(COURIER_BASE + "/today").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign", Map.of("courierId", courierId), dispatcher)
                .andExpect(status().isCreated());

        mvc.perform(get(COURIER_BASE + "/today").cookie(courier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo").value(1))
                .andExpect(jsonPath("$.done").value(0))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].parcelId").value(parcelId))
                .andExpect(jsonPath("$.items[0].stop").value("PICKUP"))
                .andExpect(jsonPath("$.items[0].task.type").value("PICKUP"))
                .andExpect(jsonPath("$.items[0].task.outcome").value("PENDING"))
                .andExpect(jsonPath("$.items[0].nextStatuses[0]").value("PICKED_UP"));
        // Not on anyone else's screen
        mvc.perform(get(COURIER_BASE + "/today").cookie(otherCourier))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(COURIER_BASE + "/{id}", parcelId).cookie(otherCourier))
                .andExpect(status().isNotFound());

        postJson(COURIER_BASE + "/" + parcelId + "/status", Map.of("status", "PICKED_UP"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.task.outcome").value("COLLECTED"))
                .andExpect(jsonPath("$.nextStatuses[0]").value("AT_BRANCH"));
        mvc.perform(get("/api/v1/dispatch/assignments").param("courierId", courierId).cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("IN_PROGRESS"));

        postJson(COURIER_BASE + "/" + parcelId + "/status", Map.of("status", "AT_BRANCH"), courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.task.outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.nextStatuses").isEmpty());

        mvc.perform(get("/api/v1/dispatch/assignments").param("courierId", courierId).cookie(dispatcher))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
        mvc.perform(get(COURIER_BASE + "/today").cookie(courier))
                .andExpect(jsonPath("$.toDo").value(0))
                .andExpect(jsonPath("$.done").value(1))
                .andExpect(jsonPath("$.items[0].nextStatuses").isEmpty());
    }

    private String createCourier(Cookie admin, String email) throws Exception {
        String body = postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Courier " + UUID.randomUUID().toString().substring(0, 6),
                "email", email,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "vehicleType", "MOTORBIKE",
                "password", "CourierInit123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private String createdCourierEmail(Cookie admin) throws Exception {
        String email = uniqueEmail();
        createCourier(admin, email);
        return email;
    }

    /** Logs a new staff account in and sets its own password (staff must change the initial one). */
    private Cookie firstLogin(String email) throws Exception {
        Cookie first = tokenCookie(login(email, "CourierInit123", false).andExpect(status().isOk()).andReturn());
        return tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", "CourierInit123", "newPassword", "CourierReady456"), first)
                .andExpect(status().isNoContent())
                .andReturn());
    }

    private Cookie staffAfterFirstLogin(String path, Map<String, String> extra, Cookie admin) throws Exception {
        String email = uniqueEmail();
        Map<String, String> body = new LinkedHashMap<>(extra);
        body.put("fullName", "Dispatcher " + UUID.randomUUID().toString().substring(0, 6));
        body.put("email", email);
        body.put("phone", uniquePhone());
        body.put("password", "CourierInit123");
        postJson(path, body, admin).andExpect(status().isCreated());
        return firstLogin(email);
    }

    private String bookParcel(Cookie customer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", Map.of("name", "Ayesha Fernando", "phone", "0771234567",
                "addressLine", "10 Galle Road", "city", "Colombo 03", "district", "COLOMBO"));
        body.put("recipient", Map.of("name", "Kamal Perera", "phone", "0719876543",
                "addressLine", "25 Kandy Road", "city", "Kandy", "district", "KANDY"));
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }
}
