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
import java.util.ArrayList;
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
 * WP3 + WP8 + WP9 end to end, with the real {@code DeliveryApi} and {@code AssignmentApi}: the dispatcher groups
 * parcels in tasks (WP8), the courier works each parcel on its own (WP9), and every step moves the real parcel (WP3).
 * Checks the parcel status, branch and history, each parcel's own completion, the task completing by itself when its
 * last parcel closes, ownership, the dispatcher's reassign/cancel/remove rules, and that refused steps change nothing.
 */
class CourierWorkflowIntegrationTest extends RealWorkflowTest {

    // ---- The whole journey ----

    /**
     * Delgoda → Gampaha branch → Matara branch → Deniyaya, three couriers and three tasks (API.md "Parcel statuses"
     * example). A second parcel in the pickup task can't be collected ("Unable to Collect").
     */
    @Test
    void parcelTravelsFromDelgodaToDeniyayaThroughThreeCouriersAndTasks() throws Exception {
        assertRealDispatch();
        StaffCourier pickupCourier = createCourier("GAMPAHA");
        StaffCourier transferCourier = createCourier("GAMPAHA");
        StaffCourier deliveryCourier = createCourier("MATARA");
        UUID parcel = bookParcel();
        UUID notReady = bookParcel();

        // 1. PICKUP of two parcels (WP8 groups them; WP9 shows each parcel as its own card)
        UUID pickup = pickupTask(pickupCourier, "GAMPAHA", parcel, notReady);
        today(pickupCourier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo").value(2))
                .andExpect(jsonPath("$.done").value(0));
        assertThat(todayIds(pickupCourier)).containsExactlyInAnyOrder(parcel.toString(), notReady.toString());
        JsonNode card = todayCard(pickupCourier, parcel);
        assertThat(card.get("task").get("assignmentId").asString()).isEqualTo(pickup.toString());
        assertThat(card.get("nextStatuses").get(0).asString()).isEqualTo("PICKED_UP");
        assertThat(card.get("canMarkUnableToCollect").asBoolean()).isTrue();
        assertThat(card.get("pickup").get("name").asString()).isEqualTo("Delgoda Spice Traders");
        assertThat(card.get("recipient").isNull()).as("a pickup never shows the recipient").isTrue();

        // 2. Collect one parcel: CREATED → PICKED_UP, task ASSIGNED → IN_PROGRESS; not completed yet
        moveTo(pickupCourier, parcel, "PICKED_UP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.PICKED_UP);
        JsonNode dispatcherPickup = dispatcherView(pickup);
        assertThat(dispatcherPickup.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(item(dispatcherPickup, parcel).get("outcome").asString()).isEqualTo("COLLECTED");

        // 3. The other parcel isn't ready: NOT_COLLECTED, the parcel stays CREATED, that parcel is completed
        unableToCollect(pickupCourier, notReady, "PARCEL_NOT_READY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.task.outcome").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.completed").value(true))
                // the collected parcel still has to be handed over, so the task stays open
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        assertThat(parcel(notReady).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(taskStatusRow(pickup)).isEqualTo("IN_PROGRESS");

        // 4. Hand over at the Gampaha branch: PICKED_UP → AT_BRANCH (GAMPAHA); last open parcel → task COMPLETED
        handOver(pickupCourier, parcel, "S. Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.receivedBy").value("S. Silva"))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        assertBranch(parcel, District.GAMPAHA);
        assertThat(dispatcherView(pickup).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(item(dispatcherView(pickup), parcel).get("receivedBy").asString()).isEqualTo("S. Silva");
        assertThat(historyIds(pickupCourier)).containsExactlyInAnyOrder(parcel.toString(), notReady.toString());

        // The parcel that wasn't collected is free for a new pickup task.
        postTask(createCourier("GAMPAHA"), "PICKUP", null, "GAMPAHA", notReady).andExpect(status().isCreated());

        // 5. TRANSFER Gampaha → Matara (still supported by dispatch): AT_BRANCH → IN_TRANSIT → AT_BRANCH (MATARA)
        UUID transfer = transferTask(transferCourier, "GAMPAHA", "MATARA", parcel);
        moveTo(transferCourier, parcel, "IN_TRANSIT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickup").isEmpty())
                .andExpect(jsonPath("$.recipient").isEmpty());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(parcel(parcel).currentBranch()).isNull();
        handOver(transferCourier, parcel, "R. Perera").andExpect(status().isOk());
        assertBranch(parcel, District.MATARA);
        assertThat(taskStatusRow(transfer)).isEqualTo("COMPLETED");

        // 6. DELIVERY from Matara: AT_BRANCH → OUT_FOR_DELIVERY → DELIVERED
        UUID delivery = deliveryTask(deliveryCourier, "MATARA", parcel);
        moveTo(deliveryCourier, parcel, "OUT_FOR_DELIVERY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.name").value("Kamala Silva"))
                .andExpect(jsonPath("$.pickup").isEmpty())
                .andExpect(jsonPath("$.codAmount").value(2500.00))
                // The fee and payment method booked by the customer survive the pickup, handover and transfer,
                // and stay apart from the cash on delivery.
                .andExpect(jsonPath("$.deliveryFee.amount").value(parcel(parcel).feeAmount().doubleValue()))
                .andExpect(jsonPath("$.deliveryFee.currency").value("LKR"))
                .andExpect(jsonPath("$.deliveryFee.paymentMethod").value("AT_PICKUP"))
                .andExpect(jsonPath("$.canMarkUnableToCollect").value(false));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);
        deliver(deliveryCourier, parcel, "Kamala Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.proof.recipientName").value("Kamala Silva"));
        DeliverySnapshot delivered = parcel(parcel);
        assertThat(delivered.status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(taskStatusRow(delivery)).isEqualTo("COMPLETED");

        // 7. The parcel's history (WP3), newest first, every step by a courier, with the branches
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

        // Each courier sees only their own part of the parcel, without contacts once it is completed.
        detail(pickupCourier, parcel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.task.type").value("PICKUP"))
                .andExpect(jsonPath("$.task.outcome").value("HANDED_OVER"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.pickup").isEmpty())
                .andExpect(jsonPath("$.history[4].branch").value("GAMPAHA"))
                .andExpect(jsonPath("$.history[0].note").doesNotExist());
        detail(deliveryCourier, parcel)
                .andExpect(jsonPath("$.task.type").value("DELIVERY"))
                .andExpect(jsonPath("$.task.outcome").value("DELIVERED"));
        mvc.perform(get(COURIER + "/history").param("trackingNumber", delivered.trackingNumber())
                        .cookie(transferCourier.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].parcelId").value(parcel.toString()))
                .andExpect(jsonPath("$.content[0].task.assignmentId").value(transfer.toString()))
                .andExpect(jsonPath("$.content[0].task.type").value("TRANSFER"));
    }

    // ---- Each parcel on its own ----

    /** A parcel is completed (today and history) as soon as its own step is done, while its task is still open. */
    @Test
    void aCompletedParcelIsInTheHistoryWhileItsTaskIsStillInProgress() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID done = bookParcel();
        UUID waiting = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", done, waiting);

        moveTo(courier, done, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, done, "S. Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));

        assertThat(taskStatusRow(task)).isEqualTo("IN_PROGRESS");
        assertThat(todayCard(courier, done).get("completed").asBoolean()).isTrue();
        assertThat(todayCard(courier, waiting).get("completed").asBoolean()).isFalse();
        detail(courier, done)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        today(courier)
                .andExpect(jsonPath("$.toDo").value(1))
                .andExpect(jsonPath("$.done").value(1))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(historyIds(courier)).containsExactly(done.toString());
    }

    @Test
    void aDeliveryParcelThatCannotBeTakenOutStaysAtTheBranchAndIsFreeForANewTask() throws Exception {
        StaffCourier courier = createCourier("MATARA");
        UUID parcel = parcelAtBranch(courier, "Deniyaya", "MATARA");
        int rows = historyRows(parcel);
        UUID task = deliveryTask(courier, "MATARA", parcel);

        unableToCollect(courier, parcel, "PARCEL_DAMAGED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_BRANCH"))
                .andExpect(jsonPath("$.task.outcome").value("NOT_LOADED"))
                .andExpect(jsonPath("$.task.problem").value("PARCEL_DAMAGED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        assertBranch(parcel, District.MATARA);
        assertThat(historyRows(parcel)).as("the parcel didn't move").isEqualTo(rows);
        assertThat(itemOutcomeRow(task, parcel)).isEqualTo("NOT_LOADED");

        postTask(courier, "DELIVERY", "MATARA", null, parcel).andExpect(status().isCreated());
    }

    // ---- Failed delivery ----

    /** A failed delivery goes back to the origin branch, and a new DELIVERY task delivers it. */
    @Test
    void failedDeliveryIsReturnedToTheBranchAndDeliveredOnANewTask() throws Exception {
        StaffCourier courier = createCourier("MATARA");
        UUID parcel = parcelAtBranch(courier, "Deniyaya", "MATARA");

        UUID first = deliveryTask(courier, "MATARA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());

        // OTHER needs a note (the courier module's rule); nothing changes.
        changeStatus(courier, parcel, Map.of("status", "FAILED", "failureReason", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY);

        // FAILED isn't completed: the courier still holds the parcel and must return it.
        changeStatus(courier, parcel, Map.of("status", "FAILED", "failureReason", "RECIPIENT_UNAVAILABLE",
                "note", "No answer, called twice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.nextStatuses[0]").value("AT_BRANCH"))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.FAILED);
        assertThat(parcel(parcel).failureReason()).isEqualTo(FailureReason.RECIPIENT_UNAVAILABLE);
        // Not in the history as a delivery yet (only its earlier pickup, which is completed).
        assertThat(completedStepTypes(courier, parcel)).containsExactly("PICKUP");
        changeStatus(courier, parcel, Map.of("status", "DELIVERED", "recipientName", "Kamala Silva",
                "proofPhotoId", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        // Returning needs who received it at the branch.
        moveTo(courier, parcel, "AT_BRANCH")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.receivedBy").exists());

        // RETURNED to the origin branch: FAILED → AT_BRANCH (MATARA); last parcel → task COMPLETED
        handOver(courier, parcel, "N. Fernando")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.outcome").value("RETURNED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        assertBranch(parcel, District.MATARA);
        assertThat(parcel(parcel).failureReason()).isNull();
        assertThat(item(dispatcherView(first), parcel).get("outcome").asString()).isEqualTo("RETURNED");
        assertThat(completedStepTypes(courier, parcel)).containsExactly("DELIVERY", "PICKUP");

        // Second attempt on a new task.
        deliveryTask(courier, "MATARA", parcel);
        moveTo(courier, parcel, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        deliver(courier, parcel, "Kamala Silva")
                .andExpect(status().isOk());
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.DELIVERED);
        assertThat(deliveries.history(parcel)).extracting(ParcelStatusEntry::status, ParcelStatusEntry::failureReason)
                .contains(tuple(ParcelStatus.FAILED, FailureReason.RECIPIENT_UNAVAILABLE));
    }

    // ---- Ownership ----

    @Test
    void aCourierCanNeitherSeeNorChangeAnotherCouriersParcel() throws Exception {
        StaffCourier owner = createCourier("GAMPAHA");
        StaffCourier other = createCourier("GAMPAHA");
        UUID parcel = bookParcel();
        UUID unrelatedParcel = bookParcel();
        UUID task = pickupTask(owner, "GAMPAHA", parcel);

        detail(other, parcel).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        moveTo(other, parcel, "PICKED_UP").andExpect(status().isNotFound());
        unableToCollect(other, parcel, "SENDER_UNAVAILABLE").andExpect(status().isNotFound());
        assertThat(todayIds(other)).doesNotContain(parcel.toString());

        // The owner can't reach a parcel that was never in their tasks.
        detail(owner, unrelatedParcel).andExpect(status().isNotFound());
        moveTo(owner, unrelatedParcel, "PICKED_UP").andExpect(status().isNotFound());

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
        pickupTask(owner, "GAMPAHA", parcel);

        for (var cookie : List.of(dispatcher, admin, customer)) {
            mvc.perform(get(COURIER + "/{id}", parcel).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            postJson(COURIER + "/" + parcel + "/status", Map.of("status", "PICKED_UP"), cookie)
                    .andExpect(status().isForbidden());
            postJson(COURIER + "/" + parcel + "/unable-to-collect", Map.of("problem", "SENDER_UNAVAILABLE"), cookie)
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
        detail(first, touched).andExpect(status().isNotFound());
        moveTo(first, touched, "PICKED_UP").andExpect(status().isNotFound());
        assertThat(todayIds(first)).doesNotContain(touched.toString());
        assertThat(todayIds(second)).contains(touched.toString(), untouched.toString());

        // The new courier starts it.
        moveTo(second, touched, "PICKED_UP").andExpect(status().isOk());

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
        assertThat(todayIds(second)).containsExactly(touched.toString());
        detail(second, untouched).andExpect(status().isNotFound());
        moveTo(second, untouched, "PICKED_UP").andExpect(status().isNotFound());
        assertThat(parcel(touched).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(parcel(untouched).status()).isEqualTo(ParcelStatus.CREATED);
    }

    @Test
    void cancelledTaskIsNoLongerOnTheListAndCanNoLongerBeWorked() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcel = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", parcel);

        mvc.perform(post(DISPATCH_TASKS + "/{id}/cancel", task).cookie(dispatcher)).andExpect(status().isOk());

        assertThat(todayIds(courier)).doesNotContain(parcel.toString());
        detail(courier, parcel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("CANCELLED"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.nextStatuses").isEmpty())
                .andExpect(jsonPath("$.canMarkUnableToCollect").value(false))
                .andExpect(jsonPath("$.pickup").isEmpty());
        moveTo(courier, parcel, "PICKED_UP")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        unableToCollect(courier, parcel, "SENDER_UNAVAILABLE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        assertThat(parcel(parcel).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(historyIds(courier)).doesNotContain(parcel.toString());
    }

    @Test
    void completedParcelStaysCompletedWhenParentCancellationIsRejected() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID completed = bookParcel();
        UUID waiting = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", completed, waiting);

        moveTo(courier, completed, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, completed, "S. Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true));

        mvc.perform(post(DISPATCH_TASKS + "/{id}/cancel", task).cookie(dispatcher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_STARTED"));

        detail(courier, completed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        today(courier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo").value(1))
                .andExpect(jsonPath("$.done").value(1));
        assertThat(historyIds(courier)).contains(completed.toString());
    }

    // ---- Tasks scheduled for a later day ----

    /**
     * {@code scheduledFor} is information only: a task the dispatcher planned for a later day is already on the
     * courier's list and the courier may work it now.
     */
    @Test
    void aTaskScheduledForALaterDayIsListedAndCanBeWorkedNow() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID later = bookParcel();
        LocalDate inThreeDays = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(3);
        JsonNode created = read(postJson(DISPATCH_TASKS, Map.of(
                "courierId", courier.id().toString(),
                "type", "PICKUP",
                "destinationBranch", "GAMPAHA",
                "scheduledFor", inThreeDays.toString(),
                "parcelIds", List.of(later.toString())), dispatcher)
                .andExpect(status().isCreated()));
        UUID laterTask = UUID.fromString(created.get("id").asString());

        assertThat(todayIds(courier)).contains(later.toString());
        moveTo(courier, later, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, later, "S. Silva")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"));
        assertThat(taskStatusRow(laterTask)).isEqualTo("COMPLETED");
        assertBranch(later, District.GAMPAHA);
    }

    // ---- Refused steps change nothing ----

    @Test
    void refusedStepsLeaveTheParcelAndTheTaskUnchanged() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID collected = bookParcel();
        UUID pending = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", collected, pending);

        // A step that doesn't follow (DELIVERED on a pickup).
        changeStatus(courier, pending, Map.of("status", "DELIVERED", "recipientName", "Kamala Silva",
                "proofPhotoId", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        assertThat(taskStatusRow(task)).isEqualTo("ASSIGNED");

        // The same step twice (a double tap): the second is refused and writes nothing.
        moveTo(courier, collected, "PICKED_UP").andExpect(status().isOk());
        int rows = historyRows(collected);
        moveTo(courier, collected, "PICKED_UP")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        assertThat(historyRows(collected)).isEqualTo(rows);

        // Collected: too late for "Unable to Collect".
        unableToCollect(courier, collected, "SENDER_UNAVAILABLE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
        assertThat(itemOutcomeRow(task, collected)).isEqualTo("COLLECTED");

        // Finish: the task completes by itself; afterwards every step is refused as closed.
        unableToCollect(courier, pending, "SENDER_UNAVAILABLE").andExpect(status().isOk());
        assertThat(taskStatusRow(task)).as("a collected parcel still to hand over").isEqualTo("IN_PROGRESS");
        handOver(courier, collected, "S. Silva").andExpect(status().isOk());
        assertThat(taskStatusRow(task)).isEqualTo("COMPLETED");
        handOver(courier, collected, "S. Silva")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        assertThat(parcel(pending).status()).isEqualTo(ParcelStatus.CREATED);
    }

    // ---- Helpers ----

    /** A parcel booked in {@code district} for a recipient there, collected and handed over at that branch. */
    private UUID parcelAtBranch(StaffCourier courier, String city, String district) throws Exception {
        UUID parcel = bookParcel(city, district, city, district);
        UUID pickup = pickupTask(courier, district, parcel);
        moveTo(courier, parcel, "PICKED_UP").andExpect(status().isOk());
        handOver(courier, parcel, "Branch desk").andExpect(status().isOk());
        assertThat(taskStatusRow(pickup)).isEqualTo("COMPLETED");
        assertBranch(parcel, District.valueOf(district));
        return parcel;
    }

    /** The task types of this parcel's entries in the courier's history, newest first. */
    private List<String> completedStepTypes(StaffCourier courier, UUID parcelId) throws Exception {
        JsonNode page = read(mvc.perform(get(COURIER + "/history").param("trackingNumber",
                parcel(parcelId).trackingNumber()).cookie(courier.cookie())).andExpect(status().isOk()));
        List<String> types = new ArrayList<>();
        for (JsonNode entry : page.get("content")) {
            assertThat(entry.get("completed").asBoolean()).isTrue();
            types.add(entry.get("task").get("type").asString());
        }
        return types;
    }

    private void assertBranch(UUID parcelId, District branch) {
        DeliverySnapshot parcel = parcel(parcelId);
        assertThat(parcel.status()).isEqualTo(ParcelStatus.AT_BRANCH);
        assertThat(parcel.currentBranch()).isEqualTo(branch);
    }
}
