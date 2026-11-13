package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentApi;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A courier step writes to two modules in one transaction: the parcel status in delivery (WP3) first, then the item
 * outcome in dispatch (WP8). When the dispatch write fails, the parcel change must be rolled back too.
 *
 * <p>Runs against the real {@code DeliveryService} and the real {@code AssignmentService}. The AssignmentApi bean is
 * a Mockito <em>spy</em>: every call goes to the real implementation unless a test makes one call fail, which is the
 * only way to stop the real code between the two writes. The rows are checked in PostgreSQL (Testcontainers).
 */
class CourierWorkflowRollbackTest extends RealWorkflowTest {

    @MockitoSpyBean
    AssignmentApi spiedAssignments;

    @Test
    void whenDispatchFailsAfterTheParcelMovedNothingIsKept() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcelId = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", parcelId);
        DeliverySnapshot before = parcel(parcelId);
        int historyBefore = historyRows(parcelId);
        int outboxBefore = outboxRows(parcelId);

        doThrow(new IllegalStateException("simulated dispatch failure"))
                .when(spiedAssignments).recordOutcome(any());
        outcome(courier, task, parcelId, Map.of("outcome", "COLLECTED", "note", "From the front desk"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        // delivery's change, its history row and its outbox event were all rolled back
        DeliverySnapshot after = parcel(parcelId);
        assertThat(after.status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(historyRows(parcelId)).isEqualTo(historyBefore);
        assertThat(outboxRows(parcelId)).isEqualTo(outboxBefore);
        // and dispatch has nothing either
        assertThat(itemOutcomeRow(task, parcelId)).isEqualTo("PENDING");
        assertThat(taskStatusRow(task)).isEqualTo("ASSIGNED");

        // Nothing was left half done: the same step works once dispatch is back.
        doCallRealMethod().when(spiedAssignments).recordOutcome(any());
        outcome(courier, task, parcelId, outcomeBody("COLLECTED")).andExpect(status().isOk());
        assertThat(parcel(parcelId).status()).isEqualTo(ParcelStatus.PICKED_UP);
        assertThat(itemOutcomeRow(task, parcelId)).isEqualTo("COLLECTED");
        assertThat(taskStatusRow(task)).isEqualTo("IN_PROGRESS");
        assertThat(historyRows(parcelId)).isEqualTo(historyBefore + 1);
    }

    /** Handover is all or nothing: a failure on the second parcel also undoes the first parcel's handover. */
    @Test
    void handoverFailingHalfwayRollsBackEveryParcel() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID first = bookParcel();
        UUID second = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", first, second);
        outcome(courier, task, first, outcomeBody("COLLECTED")).andExpect(status().isOk());
        outcome(courier, task, second, outcomeBody("COLLECTED")).andExpect(status().isOk());

        // first parcel: real; second parcel: fails
        doCallRealMethod()
                .doThrow(new IllegalStateException("simulated dispatch failure"))
                .when(spiedAssignments).recordOutcome(any());
        handover(courier, task, "S. Silva", first, second).andExpect(status().isInternalServerError());

        for (UUID parcelId : List.of(first, second)) {
            assertThat(parcel(parcelId).status()).isEqualTo(ParcelStatus.PICKED_UP);
            assertThat(parcel(parcelId).currentBranch()).isNull();
            assertThat(itemOutcomeRow(task, parcelId)).isEqualTo("COLLECTED");
        }
    }

    /**
     * A real clash, no simulated failure: the dispatcher adds a parcel to the task (another request, committed) in
     * the moment between the courier's parcel change and the outcome write. Dispatch's optimistic lock refuses the
     * courier's stale task (409 ASSIGNMENT_CHANGED), the parcel change is rolled back, and the dispatcher's change
     * is kept. Retrying with the fresh task works.
     */
    @Test
    void dispatcherChangingTheTaskAtTheSameMomentRollsBackTheCouriersStep() throws Exception {
        StaffCourier courier = createCourier("GAMPAHA");
        UUID parcelId = bookParcel();
        UUID added = bookParcel();
        UUID task = pickupTask(courier, "GAMPAHA", parcelId);

        ExecutorService otherRequest = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                // A separate thread, so the dispatcher's change runs in its own transaction and commits now.
                MvcResult result = otherRequest.submit(() -> postJson(DISPATCH_TASKS + "/" + task + "/parcels",
                        Map.of("parcelIds", List.of(added.toString())), dispatcher).andReturn())
                        .get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).as("dispatcher's add").isEqualTo(200);
                return call.callRealMethod();
            }).when(spiedAssignments).recordOutcome(any());

            outcome(courier, task, parcelId, outcomeBody("COLLECTED"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ASSIGNMENT_CHANGED"));
        } finally {
            otherRequest.shutdownNow();
        }

        assertThat(parcel(parcelId).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(itemOutcomeRow(task, parcelId)).isEqualTo("PENDING");
        assertThat(itemOutcomeRow(task, added)).as("the dispatcher's change is kept").isEqualTo("PENDING");

        doCallRealMethod().when(spiedAssignments).recordOutcome(any());
        outcome(courier, task, parcelId, outcomeBody("COLLECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(2));
        assertThat(parcel(parcelId).status()).isEqualTo(ParcelStatus.PICKED_UP);
    }
}
