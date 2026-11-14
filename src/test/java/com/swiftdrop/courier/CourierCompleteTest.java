package com.swiftdrop.courier;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Completing a task: only when every parcel is closed; it never changes a parcel status. */
class CourierCompleteTest extends CourierApiTest {

    @Test
    void aTaskWithAParcelThatWasNotCollectedCanStillBeCompleted() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot handedOver = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        DeliverySnapshot notCollected = parcel(ParcelStatus.CREATED);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(handedOver, ItemOutcome.HANDED_OVER), item(notCollected, ItemOutcome.NOT_COLLECTED)),
                handedOver, notCollected);

        postComplete(task, courier.cookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(task.id().toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.counts.problems").value(1))
                .andExpect(jsonPath("$.items").doesNotExist());

        verify(assignments).complete(task.id(), courier.id());
        // completing the task is not delivering: the parcels keep their status
        verify(delivery, never()).changeStatus(any());
        assertThat(current(handedOver).status()).isEqualTo(ParcelStatus.AT_BRANCH);
    }

    @Test
    void aParcelStillHeldByTheCourierBlocksCompletion() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot collected = parcel(ParcelStatus.PICKED_UP);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(collected, ItemOutcome.COLLECTED)), collected);

        postComplete(task, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"))
                .andExpect(jsonPath("$.message").value("Update every parcel in this task before you finish it."));
        verify(assignments, never()).complete(any(), any());
    }

    @Test
    void aFailedParcelMustBeReturnedFirst() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot delivered = parcel(ParcelStatus.DELIVERED);
        DeliverySnapshot failed = parcel(ParcelStatus.FAILED);
        AssignmentView task = given(task(courier.id(), AssignmentType.DELIVERY, AssignmentStatus.IN_PROGRESS,
                item(delivered, ItemOutcome.DELIVERED), item(failed, ItemOutcome.FAILED)), delivered, failed);

        postComplete(task, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));
    }

    @Test
    void aTaskThatWasNotStartedCannotBeCompleted() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView task = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.ASSIGNED,
                item(parcel, ItemOutcome.PENDING)), parcel);

        postComplete(task, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_FINISHED"));
    }

    @Test
    void aCompletedOrCancelledTaskIs409() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView completed = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.COMPLETED,
                item(parcel, ItemOutcome.HANDED_OVER)), parcel);
        AssignmentView cancelled = given(task(courier.id(), AssignmentType.TRANSFER, AssignmentStatus.CANCELLED,
                item(parcel, ItemOutcome.PENDING)));

        postComplete(completed, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        postComplete(cancelled, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        verify(assignments, never()).complete(any(), any());
    }

    @Test
    void aConcurrentChangeIs409InsteadOf500() throws Exception {
        Courier courier = registerCourier();
        DeliverySnapshot parcel = parcel(ParcelStatus.AT_BRANCH, District.GAMPAHA);
        AssignmentView task = given(task(courier.id(), AssignmentType.PICKUP, AssignmentStatus.IN_PROGRESS,
                item(parcel, ItemOutcome.HANDED_OVER)), parcel);
        doThrow(new ObjectOptimisticLockingFailureException("CourierAssignment", UUID.randomUUID()))
                .when(assignments).complete(any(), any());

        postComplete(task, courier.cookie())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_STEP_NOT_ALLOWED"));
    }
}
