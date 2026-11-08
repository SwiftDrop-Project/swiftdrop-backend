package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the services hand to the controllers, which map it to DTOs. */
public final class CourierViews {

    private CourierViews() {
    }

    /** Today's assignments, already in display order. */
    public record CourierToday(LocalDate date, List<AssignmentView> assignments) {

        /** Assignments not completed or cancelled yet. */
        public long open() {
            return assignments.stream().filter(assignment -> !assignment.status().isClosed()).count();
        }

        /** Assignments the courier completed today (cancelled ones are not counted). */
        public long completedToday() {
            return assignments.stream().filter(assignment -> assignment.status() == AssignmentStatus.COMPLETED)
                    .count();
        }
    }

    /**
     * One assignment and its parcels by id. A parcel that delivery doesn't know is missing from {@code parcels}; its
     * item is left out of the detail.
     */
    public record AssignmentDetail(AssignmentView assignment, Map<UUID, DeliverySnapshot> parcels) {
    }

    /** One of the courier's items for a parcel, with the assignment's type. */
    public record MyItem(UUID assignmentId, AssignmentType type, ItemOutcome outcome, boolean open) {

        static MyItem of(AssignmentView assignment, AssignmentItemView item) {
            return new MyItem(assignment.id(), assignment.type(), item.outcome(),
                    !item.isClosed() && !assignment.status().isClosed());
        }
    }

    /** One parcel the courier handles or handled, its history (newest first) and the courier's items for it. */
    public record ParcelDetail(DeliverySnapshot parcel, List<ParcelStatusEntry> history, List<MyItem> myItems) {
    }
}
