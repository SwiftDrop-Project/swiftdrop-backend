package com.swiftdrop.dispatch;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.dispatch.internal.application.ParcelEventHandler;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dispatcher's endpoints (/dispatch/**) end to end: access, both queues, the task life cycle, courier workloads,
 * issues, and the courier-facing AssignmentApi rules they depend on.
 */
class DispatcherApiTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeliveryApi deliveries;

    @Autowired
    AssignmentApi assignmentApi;

    @Autowired
    ParcelEventHandler events;

    private Cookie admin;
    private Cookie dispatcher;
    private Cookie customer;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAsAdmin();
        dispatcher = createActiveDispatcher();
        customer = registerCustomer(uniqueEmail(), uniquePhone());
    }

    // ---- Access ----

    @Test
    void onlyDispatchersAndAdminsCanUseDispatch() throws Exception {
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").cookie(customer))
                .andExpect(status().isForbidden());
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", UUID.randomUUID().toString(), "type", "PICKUP",
                "destinationBranch", "COLOMBO", "parcelIds", List.of(UUID.randomUUID().toString())), customer)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/dispatch/overview").param("branch", "COLOMBO").cookie(admin))
                .andExpect(status().isOk());
    }

    @Test
    void queueNeedsAKnownBranch() throws Exception {
        mvc.perform(get("/api/v1/dispatch/queue").cookie(dispatcher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "ATLANTIS").cookie(dispatcher))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dispatch/issues").param("branch", "COLOMBO").param("days", "90").cookie(dispatcher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.days").exists());
    }

    // ---- Pickups ----

    @Test
    void pickupTaskLifeCycle() throws Exception {
        String courierId = createCourier("COLOMBO");
        String otherCourierId = createCourier("COLOMBO");
        UUID parcelId = bookParcel("COLOMBO", "KANDY");
        events.handle(event(parcelId, ParcelStatus.CREATED));

        // The new parcel waits in Colombo's pickup queue with a suggested PICKUP task to Colombo.
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").param("kind", "PICKUP")
                        .cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].suggestedType").value("PICKUP"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].suggestedDestination")
                        .value("COLOMBO"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].from.city").value("Colombo 03"));

        String assignmentId = createTask(Map.of("courierId", courierId, "type", "PICKUP",
                "destinationBranch", "COLOMBO", "parcelIds", List.of(parcelId.toString()),
                "note", "Call before you go"));

        // Assigned parcels leave the queue, and can't be given to a second task.
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(jsonPath("$[*].parcelId", not(hasItem(parcelId.toString()))));
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", otherCourierId, "type", "PICKUP",
                "destinationBranch", "COLOMBO", "parcelIds", List.of(parcelId.toString())), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_ALREADY_ASSIGNED"));

        mvc.perform(get("/api/v1/dispatch/assignments/{id}", assignmentId).cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.note").value("Call before you go"))
                .andExpect(jsonPath("$.courier.id").value(courierId))
                .andExpect(jsonPath("$.items[0].trackingNumber").isNotEmpty())
                .andExpect(jsonPath("$.items[0].outcome").value("PENDING"));
        mvc.perform(get("/api/v1/dispatch/assignments").param("courierId", courierId).cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].parcelCount").value(1));

        // Not started yet: it can go to another courier, then be cancelled; its parcel goes back to the queue.
        postJson("/api/v1/dispatch/assignments/" + assignmentId + "/reassign",
                Map.of("courierId", otherCourierId), dispatcher)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courier.id").value(otherCourierId));
        mvc.perform(post("/api/v1/dispatch/assignments/{id}/cancel", assignmentId).cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(jsonPath("$[*].parcelId", hasItem(parcelId.toString())));
        mvc.perform(post("/api/v1/dispatch/assignments/{id}/cancel", assignmentId).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
    }

    @Test
    void createChecksTheCourierBranchesDateAndParcels() throws Exception {
        String courierId = createCourier("COLOMBO");
        UUID parcelId = bookParcel("COLOMBO", "KANDY");
        String parcel = parcelId.toString();

        postJson("/api/v1/dispatch/assignments", Map.of("type", "PICKUP", "destinationBranch", "COLOMBO",
                "parcelIds", List.of(parcel)), dispatcher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.courierId").value("Select a courier."));
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "PICKUP",
                "originBranch", "GAMPAHA", "destinationBranch", "COLOMBO", "parcelIds", List.of(parcel)), dispatcher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.originBranch").exists());
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "PICKUP",
                "destinationBranch", "COLOMBO", "scheduledFor", LocalDate.now(ZoneId.of("Asia/Colombo"))
                        .minusDays(1).toString(), "parcelIds", List.of(parcel)), dispatcher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.scheduledFor").exists());
        // The sender is in Colombo, so a Gampaha pickup doesn't suit the parcel.
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "PICKUP",
                "destinationBranch", "GAMPAHA", "parcelIds", List.of(parcel)), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_READY_FOR_TASK"));
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "PICKUP",
                "destinationBranch", "COLOMBO", "parcelIds", List.of(UUID.randomUUID().toString())), dispatcher)
                .andExpect(status().isNotFound());
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", UUID.randomUUID().toString(), "type", "PICKUP",
                "destinationBranch", "COLOMBO", "parcelIds", List.of(parcel)), dispatcher)
                .andExpect(status().isNotFound());
    }

    @Test
    void twoDispatchersAssigningOneParcelAtOnceGetOneTaskAndOneConflict() throws Exception {
        String firstCourier = createCourier("COLOMBO");
        String secondCourier = createCourier("COLOMBO");
        UUID parcelId = bookParcel("COLOMBO", "KANDY");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String courierId : List.of(firstCourier, secondCourier)) {
                Callable<Integer> attempt = () -> {
                    start.await();
                    return postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "PICKUP",
                            "destinationBranch", "COLOMBO", "parcelIds", List.of(parcelId.toString())), dispatcher)
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        Long open = jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_items"
                + " WHERE parcel_id = ? AND closed_at IS NULL", Long.class, parcelId);
        assertThat(open).isEqualTo(1L);
    }

    // ---- At a branch: transfers, deliveries, started tasks, workload and issues ----

    @Test
    void parcelsAtABranchGetATransferOrADelivery() throws Exception {
        String courierId = createCourier("COLOMBO");
        UUID courier = UUID.fromString(courierId);
        UUID inColombo = bookParcel("COLOMBO", "KANDY");
        UUID inKandy = bookParcel("COLOMBO", "KANDY");
        moveToBranch(inColombo, District.COLOMBO);
        moveToBranch(inKandy, District.KANDY);

        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "COLOMBO").param("kind", "AT_BRANCH")
                        .cookie(dispatcher))
                .andExpect(jsonPath("$[?(@.parcelId == '" + inColombo + "')].suggestedType").value("TRANSFER"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + inColombo + "')].suggestedOrigin").value("COLOMBO"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + inColombo + "')].suggestedDestination").value("KANDY"));
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "KANDY").param("kind", "AT_BRANCH")
                        .cookie(dispatcher))
                .andExpect(jsonPath("$[?(@.parcelId == '" + inKandy + "')].suggestedType").value("DELIVERY"));

        // A Kandy parcel can't be delivered from Colombo: it needs a transfer first.
        postJson("/api/v1/dispatch/assignments", Map.of("courierId", courierId, "type", "DELIVERY",
                "originBranch", "COLOMBO", "parcelIds", List.of(inColombo.toString())), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_READY_FOR_TASK"));

        String transferId = createTask(Map.of("courierId", courierId, "type", "TRANSFER", "originBranch", "COLOMBO",
                "destinationBranch", "KANDY", "parcelIds", List.of(inColombo.toString())));
        UUID transfer = UUID.fromString(transferId);

        // The courier loads it: the task has started, so it can't be cancelled, reassigned or emptied.
        assignmentApi.recordOutcome(new RecordOutcomeCommand(transfer, inColombo, courier, ItemOutcome.LOADED,
                null, null, null, null, null));
        mvc.perform(post("/api/v1/dispatch/assignments/{id}/cancel", transferId).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_STARTED"));
        postJson("/api/v1/dispatch/assignments/" + transferId + "/reassign",
                Map.of("courierId", createCourier("COLOMBO")), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_STARTED"));
        mvc.perform(delete("/api/v1/dispatch/assignments/{id}/parcels/{parcelId}", transferId, inColombo)
                        .cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));

        mvc.perform(get("/api/v1/dispatch/couriers/workload").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.courierId == '" + courierId + "')].openTasks").value(1))
                .andExpect(jsonPath("$[?(@.courierId == '" + courierId + "')].openParcels").value(1));
        mvc.perform(get("/api/v1/dispatch/overview").param("branch", "COLOMBO").cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgressTasks").isNumber())
                .andExpect(jsonPath("$.activeCouriers").isNumber());
    }

    @Test
    void parcelsAddedAndRemovedWhileATaskIsOpen() throws Exception {
        String courierId = createCourier("GALLE");
        UUID first = bookParcel("COLOMBO", "GALLE");
        UUID second = bookParcel("COLOMBO", "GALLE");
        moveToBranch(first, District.GALLE);
        moveToBranch(second, District.GALLE);

        String taskId = createTask(Map.of("courierId", courierId, "type", "DELIVERY", "originBranch", "GALLE",
                "parcelIds", List.of(first.toString())));
        postJson("/api/v1/dispatch/assignments/" + taskId + "/parcels",
                Map.of("parcelIds", List.of(second.toString())), dispatcher)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].sequence").value(2));
        postJson("/api/v1/dispatch/assignments/" + taskId + "/parcels",
                Map.of("parcelIds", List.of(second.toString())), dispatcher)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.parcelIds").exists());

        mvc.perform(delete("/api/v1/dispatch/assignments/{id}/parcels/{parcelId}", taskId, first).cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(delete("/api/v1/dispatch/assignments/{id}/parcels/{parcelId}", taskId, second).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NEEDS_PARCEL"));
    }

    @Test
    void parcelsCouriersCouldNotTakeShowUpAsIssuesAndGoBackToTheQueue() throws Exception {
        String courierId = createCourier("MATARA");
        UUID courier = UUID.fromString(courierId);
        UUID parcelId = bookParcel("COLOMBO", "MATARA");
        moveToBranch(parcelId, District.MATARA);
        UUID task = UUID.fromString(createTask(Map.of("courierId", courierId, "type", "DELIVERY",
                "originBranch", "MATARA", "parcelIds", List.of(parcelId.toString()))));

        assignmentApi.recordOutcome(new RecordOutcomeCommand(task, parcelId, courier, ItemOutcome.NOT_LOADED,
                TaskProblem.PARCEL_DAMAGED, null, "Box was wet", null, null));

        mvc.perform(get("/api/v1/dispatch/issues").param("branch", "MATARA").cookie(dispatcher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].outcome").value("NOT_LOADED"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].problem").value("PARCEL_DAMAGED"))
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].backInQueue").value(true))
                .andExpect(jsonPath("$[?(@.parcelId == '" + parcelId + "')].courier.id").value(courierId));
        mvc.perform(get("/api/v1/dispatch/queue").param("branch", "MATARA").param("kind", "AT_BRANCH")
                        .cookie(dispatcher))
                .andExpect(jsonPath("$[*].parcelId", hasItem(parcelId.toString())));
    }

    // ---- One-step assignment ----

    @Test
    void assigningOneParcelCreatesATaskForItsNextStep() throws Exception {
        String courierId = createCourier("COLOMBO");
        UUID parcelId = bookParcel("COLOMBO", "KANDY");

        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign", Map.of("courierId", courierId), dispatcher)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentType").value("PICKUP"))
                .andExpect(jsonPath("$.assignedCourierId").value(courierId))
                .andExpect(jsonPath("$.assignmentId").isNotEmpty());
        postJson("/api/v1/dispatch/parcels/" + parcelId + "/assign", Map.of("courierId", courierId), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_ALREADY_ASSIGNED"));
    }

    // ---- AssignmentApi (courier-facing) ----

    @Test
    void assignmentApiIsScopedToTheCourierAndCompletesOnlyWhenEveryParcelIsDone() throws Exception {
        String courierId = createCourier("KANDY");
        UUID courier = UUID.fromString(courierId);
        UUID parcelId = bookParcel("COLOMBO", "KANDY");
        moveToBranch(parcelId, District.KANDY);
        UUID task = UUID.fromString(createTask(Map.of("courierId", courierId, "type", "DELIVERY",
                "originBranch", "KANDY", "parcelIds", List.of(parcelId.toString()))));

        UUID stranger = UUID.randomUUID();
        assertThat(assignmentApi.findByIdForCourier(task, stranger)).isEmpty();
        assertThat(assignmentApi.findOpenForCourier(stranger, Instant.EPOCH)).isEmpty();
        assertThat(assignmentApi.findOpenForCourier(courier, Instant.now())).extracting(AssignmentView::id)
                .containsExactly(task);

        assertThatThrownBy(() -> assignmentApi.complete(task, courier))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ASSIGNMENT_NOT_FINISHED));
        assignmentApi.recordOutcome(new RecordOutcomeCommand(task, parcelId, courier, ItemOutcome.LOADED,
                null, null, null, null, null));
        AssignmentView done = assignmentApi.recordOutcome(new RecordOutcomeCommand(task, parcelId, courier,
                ItemOutcome.DELIVERED, null, null, null, null, "Kamal Perera"));
        assertThat(done.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        assertThat(assignmentApi.complete(task, courier).status()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(assignmentApi.findClosedForCourier(courier, parcelId,
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(assignmentApi.findItemsForCourierAndParcel(courier, parcelId)).hasSize(1);
    }

    // ---- The pickup queue kept from events ----

    @Test
    void pickupQueueFollowsParcelEventsAndIgnoresLateOnes() throws Exception {
        UUID parcelId = bookParcel("KALUTARA", "KANDY");
        ParcelStatusChanged created = event(parcelId, ParcelStatus.CREATED);
        events.handle(created);
        events.handle(created); // a duplicate changes nothing
        assertThat(queuedCount(parcelId)).isEqualTo(1);

        UUID courier = UUID.randomUUID();
        DeliverySnapshot picked = deliveries.changeStatus(new ChangeStatusCommand(parcelId, ParcelStatus.PICKED_UP,
                courier, "COURIER", null, null, null, null));
        events.handle(event(parcelId, ParcelStatus.PICKED_UP, picked.version()));
        assertThat(queuedCount(parcelId)).isZero();

        events.handle(created); // the CREATED event arriving late doesn't bring the parcel back
        assertThat(queuedCount(parcelId)).isZero();
    }

    // ---- Helpers ----

    private Long queuedCount(UUID parcelId) {
        return jdbc.queryForObject("SELECT count(*) FROM dispatch.pickup_queue WHERE parcel_id = ?", Long.class,
                parcelId);
    }

    private ParcelStatusChanged event(UUID parcelId, ParcelStatus toStatus) {
        return event(parcelId, toStatus, deliveries.findById(parcelId).orElseThrow().version());
    }

    private ParcelStatusChanged event(UUID parcelId, ParcelStatus toStatus, long version) {
        DeliverySnapshot parcel = deliveries.findById(parcelId).orElseThrow();
        return new ParcelStatusChanged(UUID.randomUUID(), parcelId, version, parcel.trackingNumber(),
                parcel.customerId(), null, null, toStatus, parcel.customerId(), "CUSTOMER", null, null, Instant.now(),
                null);
    }

    /** Picks the parcel up and drops it at the branch, as the couriers would. */
    private void moveToBranch(UUID parcelId, District branch) {
        UUID courier = UUID.randomUUID();
        deliveries.changeStatus(new ChangeStatusCommand(parcelId, ParcelStatus.PICKED_UP, courier, "COURIER",
                null, null, null, null));
        deliveries.changeStatus(new ChangeStatusCommand(parcelId, ParcelStatus.AT_BRANCH, courier, "COURIER",
                null, null, null, null, branch));
    }

    private String createTask(Map<String, Object> body) throws Exception {
        MvcResult result = postJson("/api/v1/dispatch/assignments", body, dispatcher)
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private String createCourier(String branch) throws Exception {
        String body = postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Courier " + UUID.randomUUID().toString().substring(0, 6),
                "email", uniqueEmail(),
                "phone", uniquePhone(),
                "branch", branch,
                "vehicleType", "MOTORBIKE",
                "password", "CourierInit123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private Cookie createActiveDispatcher() throws Exception {
        String email = uniqueEmail();
        postJson("/api/v1/admin/dispatchers", Map.of(
                "fullName", "Dispatcher " + UUID.randomUUID().toString().substring(0, 6),
                "email", email,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "password", "DispInit123"), admin)
                .andExpect(status().isCreated());
        Cookie first = tokenCookie(login(email, "DispInit123", false).andExpect(status().isOk()).andReturn());
        return tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", "DispInit123", "newPassword", "DispReady456"), first)
                .andExpect(status().isNoContent())
                .andReturn());
    }

    private UUID bookParcel(String pickupDistrict, String recipientDistrict) throws Exception {
        Map<String, Object> pickup = new HashMap<>(Map.of(
                "name", "Ayesha Fernando",
                "phone", "0771234567",
                "addressLine", "10 Galle Road",
                "city", pickupDistrict.equals("COLOMBO") ? "Colombo 03" : "Town",
                "district", pickupDistrict));
        Map<String, Object> recipient = Map.of(
                "name", "Kamal Perera",
                "phone", "0719876543",
                "addressLine", "25 Kandy Road",
                "city", "City",
                "district", recipientDistrict);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("1.50"));
        String response = postJson("/api/v1/deliveries", body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }
}
