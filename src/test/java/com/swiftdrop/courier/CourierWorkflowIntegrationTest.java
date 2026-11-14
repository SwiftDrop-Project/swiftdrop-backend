package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP3 + WP8 + WP9 end to end, with the real {@code DeliveryApi} and {@code AssignmentApi}: the dispatcher's tasks
 * (WP8) drive the courier's steps (WP9), and every step moves the real parcel (WP3). Checks the parcel status, branch
 * and history, the task and item states as both the courier and the dispatcher see them, ownership, the dispatcher's
 * reassign/cancel/remove rules, and that refused steps change nothing.
 */
class CourierWorkflowIntegrationTest extends RealWorkflowTest {

    // ---- The whole journey ----

    /**
     * Delgoda → Gampaha branch → Matara branch → Deniyaya, three couriers and three tasks (API.md "Parcel statuses"
     * example). A second parcel in the pickup task can't be collected and goes back to the queue.
     */
    @Test
    void parcelTravelsFromDelgodaToDeniyayaThroughThreeCouriersAndTasks() throws Exception {
        assertRealDispatch();
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier transferCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("MATARA");
        UUID parcel = bookParcel();
        UUID notReady = bookParcel();

        // 1. PICKUP (WP8 creates it; WP9 shows it to the courier)
        UUID pickup = pickupTask(pickupCourier, "GAMPAHA", parcel, notReady);
        JsonNode todayBody = read(today(pickupCourier).andExpect(status().isOk()));
        assertThat(todayBody.get("open").asLong()).isEqualTo(1);
        assertThat(todayIds(pickupCourier)).containsExactly(pickup.toString());
        taskDetail(pickupCourier, pickup)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PICKUP"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.destinationBranch").value("GAMPAHA"))
                .andExpect(jsonPath("$.counts.total").value(2))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("COLLECTED"))
                .andExpect(jsonPath("$.items[0].contact.name").value("Delgoda Spice Traders"));

        // 2. Collect one parcel: parcel CREATED → PICKED_UP, task ASSIGNED → IN_PROGRESS
        JsonNode afterCollect = read(outcome(pickupCourier, pickup, parcel, Map.of("outcome", "COLLECTED",
                "note", "From the front desk")).andExpect(status().isOk()));
        assertThat(afterCollect.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(item(afterCollect, parcel).get("parcelStatus").asString()).isEqualTo("PICKED_UP");
        assertThat(item(afterCollect, parcel).get("readyForHandover").asBoolean()).isTrue();
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.PICKED_UP);

        // The dispatcher (WP8) sees the courier's progress.
        JsonNode dispatcherPickup = dispatcherView(pickup);
        assertThat(dispatcherPickup.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(item(dispatcherPickup, parcel).get("outcome").asString()).isEqualTo("COLLECTED");

        // 3. The other parcel isn't ready: item closes, parcel stays CREATED
        outcome(pickupCourier, pickup, notReady, Map.of("outcome", "NOT_COLLECTED",
                "problem", "PARCEL_NOT_READY")).andExpect(status().isOk());
        assertThat(parcel(notReady).status()).isEqualTo(ParcelStatus.CREATED);

        // Can't finish while a collected parcel is still with the courier.
        complete(pickupCourier, pickup)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));

        // 4. Hand over at the Gampaha branch: PICKED_UP → AT_BRANCH (GAMPAHA)
        JsonNode afterHandover = read(handover(pickupCourier, pickup, "S. Silva", parcel)
                .andExpect(status().isOk()));
        assertThat(afterHandover.get("canComplete").asBoolean()).isTrue();
        assertThat(item(afterHandover, parcel).get("receivedBy").asString()).isEqualTo("S. Silva");
        assertBranch(parcel, District.GAMPAHA);

        // 5. Complete the pickup: task COMPLETED, the parcel itself does not move
        complete(pickupCourier, pickup)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertBranch(parcel, District.GAMPAHA);
        assertThat(dispatcherView(pickup).get("status").asString()).isEqualTo("COMPLETED");

        // The parcel that wasn't collected is free for a new pickup task.
        postTask(createCourier("GAMPAHA"), "PICKUP", null, "GAMPAHA", notReady).andExpect(status().isCreated());

        // 6. TRANSFER Gampaha → Matara: AT_BRANCH → IN_TRANSIT → AT_BRANCH (MATARA)
        UUID transfer = transferTask(transferCourier, "GAMPAHA", "MATARA", parcel);
        JsonNode loaded = read(outcome(transferCourier, transfer, parcel, outcomeBody("LOADED"))
                .andExpect(status().isOk()));
        assertThat(item(loaded, parcel).get("contact").isNull()).as("no contact on a transfer").isTrue();
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(parcel(parcel).currentBranch()).isNull();
        handover(transferCourier, transfer, "R. Perera", parcel).andExpect(status().isOk());
        assertBranch(parcel, District.MATARA);
        complete(transferCourier, transfer).andExpect(status().isOk());

        // 7. DELIVERY from Matara: AT_BRANCH → OUT_FOR_DELIVERY → DELIVERED
        UUID delivery = deliveryTask(deliveryCourier, "MATARA", parcel);
        JsonNode out = read(outcome(deliveryCourier, delivery, parcel, outcomeBody("LOADED"))
                .andExpect(status().isOk()));
        assertThat(item(out, parcel).get("contact").get("name").asString()).isEqualTo("Kamala Silva");
        assertThat(item(out, parcel).get("codAmount").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        outcome(deliveryCourier, delivery, parcel, Map.of("outcome", "DELIVERED", "recipientName", "Kamala Silva"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canComplete").value(true));
        DeliverySnapshot delivered = parcel(parcel);
        assertThat(delivered.status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(delivered.proofRecipientName()).isEqualTo("Kamala Silva");
        complete(deliveryCourier, delivery).andExpect(status().isOk());

        // 8. The parcel's history (WP3), newest first, every step by a courier, with the branches
        List<ParcelStatusEntry> history = deliveries.history(parcel);
        assertThat(history).extracting(ParcelStatusEntry::status, ParcelStatusEntry::branch).containsExactly(
                tuple(ParcelStatus.DELIVERED, null),
                tuple(ParcelStatus.OUT_FOR_DELIVERY, null),
                tuple(ParcelStatus.AT_BRANCH, District.MATARA),
                tuple(ParcelStatus.IN_TRANSIT, null),
                tuple(ParcelStatus.AT_BRANCH, District.GAMPAHA),
                tuple(ParcelStatus.PICKED_UP, null),
                tuple(ParcelStatus.CREATED, null));
        assertThat(history.subList(0, 6)).extracting(ParcelStatusEntry::actorRole).containsOnly("COURIER");
        assertThat(outboxRows(parcel)).as("one ParcelStatusChanged per change").isEqualTo(7);

        // Each courier sees only their own part of the parcel, and their task in the history.
        courierParcel(pickupCourier, parcel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.myItems.length()").value(1))
                .andExpect(jsonPath("$.myItems[0].type").value("PICKUP"))
                .andExpect(jsonPath("$.contact").isEmpty())
                .andExpect(jsonPath("$.history[4].branch").value("GAMPAHA"));
        courierParcel(deliveryCourier, parcel)
                .andExpect(jsonPath("$.myItems[0].type").value("DELIVERY"))
                .andExpect(jsonPath("$.myItems[0].outcome").value("DELIVERED"));
        mvc.perform(get(TASKS + "/history").param("trackingNumber", delivered.trackingNumber())
                        .cookie(transferCourier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].assignmentId").value(transfer.toString()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    // ---- Failed delivery ----

    /** A failed delivery goes back to the origin branch, and a new DELIVERY task delivers it. */
    @Test
    void failedDeliveryIsReturnedToTheBranchAndDeliveredOnANewTask() throws Exception {
        StaffCourier courier = createCourier("MATARA");
        UUID parcel = parcelAtBranch(courier, "Deniyaya", "MATARA");

        UUID first = deliveryTask(courier, "MATARA", parcel);
        outcome(courier, first, parcel, outcomeBody("LOADED")).andExpect(status().isOk());

        // OTHER needs a note (the courier module's rule); nothing changes.
        outcome(courier, first, parcel, Map.of("outcome", "FAILED", "failureReason", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);

        JsonNode failed = read(outcome(courier, first, parcel, Map.of("outcome", "FAILED",
                "failureReason", "RECIPIENT_UNAVAILABLE", "note", "No answer, called twice"))
                .andExpect(status().isOk()));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.FAILED);
        assertThat(parcel(parcel).failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        // FAILED doesn't close the item: the courier still holds the parcel.
        JsonNode failedItem = item(failed, parcel);
        assertThat(failedItem.get("allowedOutcomes").isEmpty()).isTrue();
        assertThat(failedItem.get("readyForHandover").asBoolean()).isTrue();
        assertThat(failed.get("canComplete").asBoolean()).isFalse();
        complete(courier, first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));
        outcome(courier, first, parcel, Map.of("outcome", "DELIVERED", "recipientName", "Kamala Silva"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));

        // RETURNED to the origin branch: FAILED → AT_BRANCH (MATARA), failure reason cleared on the parcel
        handover(courier, first, "N. Fernando", parcel).andExpect(status().isOk());
        assertBranch(parcel, District.MATARA);
        assertThat(parcel(parcel).failureReason()).isNull();
        JsonNode done = read(complete(courier, first).andExpect(status().isOk()));
        assertThat(done.get("counts").get("problems").asInt()).isEqualTo(1);
        assertThat(item(dispatcherView(first), parcel).get("outcome").asString()).isEqualTo("RETURNED");

        // Second attempt on a new task.
        UUID second = deliveryTask(courier, "MATARA", parcel);
        outcome(courier, second, parcel, outcomeBody("LOADED")).andExpect(status().isOk());
        outcome(courier, second, parcel, Map.of("outcome", "DELIVERED", "recipientName", "Kamala Silva"))
                .andExpect(status().isOk());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(deliveries.history(parcel)).extracting(ParcelStatusEntry::status, ParcelStatusEntry::failureReason)
                .contains(tuple(ParcelStatus.FAILED, FailureReason.RECIPIENT_UNAVAILABLE));
    }

    // ---- Ownership ----

    @Test
    void aCourierCanNeitherSeeNorChangeAnotherCouriersTaskOrParcel() throws Exception {
        StaffCourier owner = createCourier("GAMPAHA");
        StaffCourier other = createCourier("GAMPAHA");
        UUID parcel = bookParcel();
        UUID unrelatedParcel = bookParcel();
        UUID task = pickupTask(owner, "GAMPAHA", parcel);

        taskDetail(other, task).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        outcome(other, task, parcel, outcomeBody("COLLECTED")).andExpect(status().isNotFound());
        handover(other, task, "S. Silva", parcel).andExpect(status().isNotFound());
        complete(other, task).andExpect(status().isNotFound());
        courierParcel(other, parcel).andExpect(status().isNotFound());
        assertThat(todayIds(other)).doesNotContain(task.toString());

        // The owner can't reach a parcel that isn't in the task, nor an unknown task.
        outcome(owner, task, unrelatedParcel, outcomeBody("COLLECTED")).andExpect(status().isNotFound());
        courierParcel(owner, unrelatedParcel).andExpect(status().isNotFound());
        taskDetail(owner, UUID.randomUUID()).andExpect(status().isNotFound());

        // Nothing moved.
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(parcel(unrelatedParcel).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(itemOutcomeRow(task, parcel)).isEqualTo("PENDING");
        assertThat(taskStatusRow(task)).isEqualTo("ASSIGNED");
    }

    @Test
    void onlyCouriersMayUseCourierEndpoints() throws Exception {
        StaffCourier owner = createCourier("GAMPAHA");
        UUID parcel = bookParcel();
        UUID task = pickupTask(owner, "GAMPAHA", parcel);

        for (var cookie : List.of(dispatcher, admin, customer)) {
            mvc.perform(get(TASKS + "/{id}", task).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            postJson(TASKS + "/" + task + "/items/" + parcel + "/outcome", outcomeBody("COLLECTED"), cookie)
                    .andExpect(status().isForbidden());
        }
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
    }

    // ---- The dispatcher's reassign, cancel and remove rules (WP8) seen by the courier ----

    @Test
    void reassignedTaskMovesToTheNewCourierAndAStartedTaskCanNoLongerBeChangedByTheDispatcher() throws Exception {
        StaffCourier first = createCourier("GAMPAHA");
        StaffCourier second = createCourier("GAMPAHA");
        UUID touched = bookParcel();
        UUID untouched = bookParcel();
        UUID task = pickupTask(first, "GAMPAHA", touched, untouched);

        // Not started yet: the dispatcher may give it to another courier.
        postJson(DISPATCH_TASKS + "/" + task + "/reassign", Map.of("courierId", second.id().toString()), dispatcher)
                .andExpect(status().isOk());
        taskDetail(first, task).andExpect(status().isNotFound());
        outcome(first, task, touched, outcomeBody("COLLECTED")).andExpect(status().isNotFound());
        assertThat(todayIds(first)).doesNotContain(task.toString());
        assertThat(todayIds(second)).contains(task.toString());

        // The new courier starts it.
        outcome(second, task, touched, outcomeBody("COLLECTED")).andExpect(status().isOk());

        // Started: no reassign, no cancel, and a parcel the courier updated can't be taken out.
        postJson(DISPATCH_TASKS + "/" + task + "/reassign", Map.of("courierId", first.id().toString()), dispatcher)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_STARTED"));
        mvc.perform(post(DISPATCH_TASKS + "/{id}/cancel", task).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_STARTED"));
        mvc.perform(delete(DISPATCH_TASKS + "/{id}/parcels/{parcelId}", task, touched).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));

        // A parcel the courier hasn't touched may still be taken out; the courier no longer sees it.
        mvc.perform(delete(DISPATCH_TASKS + "/{id}/parcels/{parcelId}", task, untouched).cookie(dispatcher))
                .andExpect(status().isOk());
        taskDetail(second, task).andExpect(jsonPath("$.counts.total").value(1));
        outcome(second, task, untouched, outcomeBody("COLLECTED")).andExpect(status().isNotFound());
        assertThat(parcel(touched).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(parcel(untouched).status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void cancelledTaskIsShownToTheCourierButCanNoLongerBeWorked() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", parcel);

        mvc.perform(post(DISPATCH_TASKS + "/{id}/cancel", task).cookie(dispatcher)).andExpect(status().isOk());

        // Cancelled today: still on today's list, closed, nothing allowed.
        assertThat(todayIds(courier)).contains(task.toString());
        taskDetail(courier, task)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.items[0].allowedOutcomes").isEmpty())
                .andExpect(jsonPath("$.items[0].contact").isEmpty());
        outcome(courier, task, parcel, outcomeBody("COLLECTED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        complete(courier, task)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
    }

    // ---- Tasks scheduled for a later day ----

    /**
     * Current behaviour, kept on purpose: {@code scheduledFor} is information only. A task the dispatcher planned for a
     * later day is already on the courier's list (with its date), ordered by {@code assignedAt} like any other, and
     * the courier may work it now.
     */
    @Test
    void aTaskScheduledForALaterDayIsListedWithItsDateAndCanBeWorkedNow() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID later = bookParcel();
        UUID now = bookParcel();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        LocalDate inThreeDays = today.plusDays(3);
        JsonNode created = read(postJson(DISPATCH_TASKS, Map.of(
                "courierId", courier.id().toString(),
                "type", "PICKUP",
                "destinationBranch", "GAMPAHA",
                "scheduledFor", inThreeDays.toString(),
                "parcelIds", List.of(later.toString())), dispatcher)
                .andExpect(status().isCreated()));
        UUID laterTask = UUID.fromString(created.get("id").asString());
        UUID todayTask = pickupTask(courier, "GAMPAHA", now);

        // Listed today, with its own date; assigned first, so listed first (not ordered by scheduledFor).
        today(courier)
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.assignments[0].assignmentId").value(laterTask.toString()))
                .andExpect(jsonPath("$.assignments[0].scheduledFor").value(inThreeDays.toString()))
                .andExpect(jsonPath("$.assignments[1].assignmentId").value(todayTask.toString()))
                .andExpect(jsonPath("$.assignments[1].scheduledFor").value(today.toString()));
        taskDetail(courier, laterTask)
                .andExpect(jsonPath("$.scheduledFor").value(inThreeDays.toString()))
                .andExpect(jsonPath("$.items[0].allowedOutcomes[0]").value("COLLECTED"));

        // No restriction by date: the whole task can be done today.
        outcome(courier, laterTask, later, outcomeBody("COLLECTED")).andExpect(status().isOk());
        handover(courier, laterTask, "S. Silva", later).andExpect(status().isOk());
        complete(courier, laterTask)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.scheduledFor").value(inThreeDays.toString()));
        assertThat(parcel(later).status()).isEqualTo(ParcelStatus.AT_BRANCH);
        assertThat(parcel(later).currentBranch()).isEqualTo(District.GAMPAHA);
    }

    // ---- Refused steps change nothing ----

    @Test
    void refusedStepsLeaveTheParcelAndTheTaskUnchanged() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID collected = bookParcel();
        UUID pending = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", collected, pending);

        // A step that doesn't follow (DELIVERED on a pickup).
        outcome(courier, task, pending, Map.of("outcome", "DELIVERED", "recipientName", "Kamala Silva"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        // A handover outcome on the outcome endpoint.
        outcome(courier, task, pending, outcomeBody("HANDED_OVER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.outcome").value("Use Hand over for this step."));
        assertThat(taskStatusRow(task)).isEqualTo("ASSIGNED");

        // The same step twice (a double tap): the second is refused and writes nothing.
        outcome(courier, task, collected, outcomeBody("COLLECTED")).andExpect(status().isOk());
        int rows = historyRows(collected);
        outcome(courier, task, collected, outcomeBody("COLLECTED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        assertThat(historyRows(collected)).isEqualTo(rows);

        // Handover is all or nothing: one parcel not ready → neither moves.
        handover(courier, task, "S. Silva", collected, pending)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        assertThat(parcel(collected).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(parcel(pending).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(itemOutcomeRow(task, collected)).isEqualTo("COLLECTED");

        // Finish properly, then a second complete is refused.
        outcome(courier, task, pending, Map.of("outcome", "NOT_COLLECTED", "problem", "SENDER_UNAVAILABLE"))
                .andExpect(status().isOk());
        handover(courier, task, "S. Silva", collected).andExpect(status().isOk());
        complete(courier, task).andExpect(status().isOk());
        complete(courier, task)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        handover(courier, task, "S. Silva", collected)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
    }

    // ---- Helpers ----

    /** A parcel booked in {@code district} for a recipient there, collected and handed over at that branch. */
    private UUID parcelAtBranch(StaffCourier courier, String city, String district) throws Exception {
        UUID parcel = bookParcel(city, district, city, district);
        UUID pickup = pickupTask(courier, district, parcel);
        outcome(courier, pickup, parcel, outcomeBody("COLLECTED")).andExpect(status().isOk());
        handover(courier, pickup, "Branch desk", parcel).andExpect(status().isOk());
        complete(courier, pickup).andExpect(status().isOk());
        assertBranch(parcel, District.valueOf(district));
        return parcel;
    }

    private void assertBranch(UUID parcelId, District branch) {
        DeliverySnapshot parcel = parcel(parcelId);
        assertThat(parcel.status()).isEqualTo(ParcelStatus.AT_BRANCH);
        assertThat(parcel.currentBranch()).isEqualTo(branch);
    }
}
