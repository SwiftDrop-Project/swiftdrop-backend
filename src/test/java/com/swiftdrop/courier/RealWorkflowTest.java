package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.AssignmentApi;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the courier tests that run against the REAL delivery (WP3) and dispatch (WP8) modules: no mocks.
 * Everything is set up through the real HTTP endpoints, as the people would do it: the admin creates
 * the couriers and a dispatcher, a customer books the parcels, the dispatcher creates the tasks, and the couriers work
 * them. Parcels go from Delgoda (GAMPAHA) to Deniyaya (MATARA) unless a test says otherwise.
 *
 * <p>Couriers and dispatchers created by the admin must change their initial password first
 * (PASSWORD_CHANGE_REQUIRED), so the helpers do that and return the fresh cookie.
 */
abstract class RealWorkflowTest extends IntegrationTest {

    protected static final String TASKS = "/api/v1/courier/assignments";
    protected static final String COURIER_PARCELS = "/api/v1/courier/parcels";
    protected static final String DISPATCH_TASKS = "/api/v1/dispatch/assignments";

    @Autowired
    protected DeliveryApi deliveries;

    @Autowired
    protected AssignmentApi assignmentApi;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Cookie admin;
    protected Cookie dispatcher;
    protected Cookie customer;

    /** A courier created by the admin, with the password already changed. */
    protected record StaffCourier(UUID id, Cookie cookie) {
    }

    @BeforeEach
    void setUpPeople() throws Exception {
        admin = loginAsAdmin();
        dispatcher = createDispatcher();
        customer = registerCustomer(uniqueEmail(), uniquePhone());
    }

    /** The real dispatch implementation answers, not a test double. */
    protected void assertRealDispatch() {
        assertThat(AopUtils.getTargetClass(assignmentApi).getName())
                .isEqualTo("com.swiftdrop.dispatch.internal.application.AssignmentService");
    }

    // ---- People ----

    protected StaffCourier createCourier(String branch) throws Exception {
        String email = uniqueEmail();
        String body = postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Courier " + UUID.randomUUID().toString().substring(0, 6),
                "email", email,
                "phone", uniquePhone(),
                "branch", branch,
                "vehicleType", "MOTORBIKE",
                "password", "CourierInit123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());
        return new StaffCourier(id, changeInitialPassword(email, "CourierInit123", "CourierReady456"));
    }

    private Cookie createDispatcher() throws Exception {
        String email = uniqueEmail();
        postJson("/api/v1/admin/dispatchers", Map.of(
                "fullName", "Dispatcher " + UUID.randomUUID().toString().substring(0, 6),
                "email", email,
                "phone", uniquePhone(),
                "branch", "GAMPAHA",
                "password", "DispInit123"), admin)
                .andExpect(status().isCreated());
        return changeInitialPassword(email, "DispInit123", "DispReady456");
    }

    private Cookie changeInitialPassword(String email, String initial, String chosen) throws Exception {
        Cookie first = tokenCookie(login(email, initial, false).andExpect(status().isOk()).andReturn());
        return tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", initial, "newPassword", chosen), first)
                .andExpect(status().isNoContent())
                .andReturn());
    }

    // ---- Parcels (WP3) ----

    /** A customer booking from Delgoda (GAMPAHA) to Deniyaya (MATARA), status CREATED. */
    protected UUID bookParcel() throws Exception {
        return bookParcel("Delgoda", "GAMPAHA", "Deniyaya", "MATARA");
    }

    protected UUID bookParcel(String pickupCity, String pickupDistrict, String recipientCity,
                              String recipientDistrict) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", Map.of("name", "Delgoda Spice Traders", "phone", "0771234567",
                "addressLine", "14 Kandy Road", "city", pickupCity, "district", pickupDistrict));
        body.put("recipient", Map.of("name", "Kamala Silva", "phone", "0719876543",
                "addressLine", "45 Tea Estate Road", "city", recipientCity, "district", recipientDistrict));
        body.put("weightKg", new BigDecimal("1.20"));
        // Packed measurements are required since WP3 PR6 (pricing chooses the vehicle from them).
        body.put("lengthCm", 40);
        body.put("widthCm", 30);
        body.put("heightCm", 12);
        body.put("codAmount", new BigDecimal("2500.00"));
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    /** The parcel as delivery (WP3) has it now. */
    protected DeliverySnapshot parcel(UUID parcelId) {
        return deliveries.findById(parcelId).orElseThrow();
    }

    protected int historyRows(UUID parcelId) {
        return count("select count(*) from delivery.parcel_status_history where parcel_id = ?", parcelId);
    }

    protected int outboxRows(UUID parcelId) {
        return count("select count(*) from delivery.outbox_events where parcel_id = ?", parcelId);
    }

    protected String itemOutcomeRow(UUID assignmentId, UUID parcelId) {
        return jdbc.queryForObject("select outcome from dispatch.assignment_items where assignment_id = ?"
                + " and parcel_id = ?", String.class, assignmentId, parcelId);
    }

    protected String taskStatusRow(UUID assignmentId) {
        return jdbc.queryForObject("select status from dispatch.courier_assignments where id = ?", String.class,
                assignmentId);
    }

    private int count(String sql, UUID parcelId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, parcelId);
        return value == null ? 0 : value;
    }

    // ---- Tasks (WP8, as the dispatcher) ----

    protected UUID pickupTask(StaffCourier courier, String destination, UUID... parcelIds) throws Exception {
        return createTask(courier, "PICKUP", null, destination, parcelIds);
    }

    protected UUID transferTask(StaffCourier courier, String origin, String destination, UUID... parcelIds)
            throws Exception {
        return createTask(courier, "TRANSFER", origin, destination, parcelIds);
    }

    protected UUID deliveryTask(StaffCourier courier, String origin, UUID... parcelIds) throws Exception {
        return createTask(courier, "DELIVERY", origin, null, parcelIds);
    }

    protected ResultActions postTask(StaffCourier courier, String type, String origin, String destination,
                                     UUID... parcelIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courierId", courier.id().toString());
        body.put("type", type);
        if (origin != null) {
            body.put("originBranch", origin);
        }
        if (destination != null) {
            body.put("destinationBranch", destination);
        }
        body.put("parcelIds", Arrays.stream(parcelIds).map(UUID::toString).toList());
        return postJson(DISPATCH_TASKS, body, dispatcher);
    }

    private UUID createTask(StaffCourier courier, String type, String origin, String destination,
                            UUID... parcelIds) throws Exception {
        JsonNode task = read(postTask(courier, type, origin, destination, parcelIds).andExpect(status().isCreated()));
        return UUID.fromString(task.get("id").asString());
    }

    /** The task as the dispatcher sees it (GET /dispatch/assignments/{id}). */
    protected JsonNode dispatcherView(UUID assignmentId) throws Exception {
        return read(mvc.perform(get(DISPATCH_TASKS + "/{id}", assignmentId).cookie(dispatcher))
                .andExpect(status().isOk()));
    }

    // ---- Courier calls (WP9) ----

    protected ResultActions outcome(StaffCourier courier, UUID assignmentId, UUID parcelId, Map<String, ?> body)
            throws Exception {
        return postJson(TASKS + "/" + assignmentId + "/items/" + parcelId + "/outcome", body, courier.cookie());
    }

    protected ResultActions handover(StaffCourier courier, UUID assignmentId, String receivedBy, UUID... parcelIds)
            throws Exception {
        return postJson(TASKS + "/" + assignmentId + "/handover", Map.of(
                "parcelIds", Arrays.stream(parcelIds).map(UUID::toString).toList(),
                "receivedBy", receivedBy), courier.cookie());
    }

    protected ResultActions complete(StaffCourier courier, UUID assignmentId) throws Exception {
        return mvc.perform(post(TASKS + "/{id}/complete", assignmentId).cookie(courier.cookie()));
    }

    protected ResultActions taskDetail(StaffCourier courier, UUID assignmentId) throws Exception {
        return mvc.perform(get(TASKS + "/{id}", assignmentId).cookie(courier.cookie()));
    }

    protected ResultActions today(StaffCourier courier) throws Exception {
        return mvc.perform(get(TASKS + "/today").cookie(courier.cookie()));
    }

    protected ResultActions courierParcel(StaffCourier courier, UUID parcelId) throws Exception {
        return mvc.perform(get(COURIER_PARCELS + "/{id}", parcelId).cookie(courier.cookie()));
    }

    protected static Map<String, Object> outcomeBody(String outcome) {
        return Map.of("outcome", outcome);
    }

    /** The item for this parcel in a task detail response (courier or dispatcher view). */
    protected static JsonNode item(JsonNode task, UUID parcelId) {
        for (JsonNode item : task.get("items")) {
            if (item.get("parcelId").asString().equals(parcelId.toString())) {
                return item;
            }
        }
        throw new AssertionError("parcel " + parcelId + " is not in the task");
    }

    /** The assignment ids in GET /courier/assignments/today. */
    protected List<String> todayIds(StaffCourier courier) throws Exception {
        JsonNode body = read(today(courier).andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        for (JsonNode task : body.get("assignments")) {
            ids.add(task.get("assignmentId").asString());
        }
        return ids;
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
