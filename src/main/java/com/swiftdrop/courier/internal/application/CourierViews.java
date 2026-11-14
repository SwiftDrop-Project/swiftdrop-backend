package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.time.LocalDate;
import java.util.List;

/** What the service hands to the controller, which maps it to DTOs. */
public final class CourierViews {

    private CourierViews() {
    }

    /** A parcel and the dispatch task step the courier has for it. */
    public record CourierParcel(DeliverySnapshot parcel, CourierTask task) {
    }

    /** Today's parcels, already in display order. */
    public record CourierToday(LocalDate date, List<CourierParcel> parcels) {

        /** Parcels whose step the courier finished (delivered, handed over at a branch, returned). */
        public long done() {
            return parcels.stream().filter(parcel -> parcel.task().closed()).count();
        }

        public long toDo() {
            return parcels.size() - done();
        }
    }

    /** One parcel, its history (newest first) and the courier's task step for it. */
    public record ParcelDetail(DeliverySnapshot parcel, List<ParcelStatusEntry> history, CourierTask task) {
    }
}
