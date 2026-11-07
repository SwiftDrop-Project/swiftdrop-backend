package com.swiftdrop.courier.internal.application;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.time.LocalDate;
import java.util.List;

/** What the service hands to the controller, which maps it to DTOs. */
public final class CourierViews {

    private CourierViews() {
    }

    /** Today's parcels, already in display order. */
    public record CourierToday(LocalDate date, List<DeliverySnapshot> parcels) {

        public long done() {
            return parcels.stream().filter(parcel -> parcel.status().isFinished()).count();
        }

        public long toDo() {
            return parcels.size() - done();
        }
    }

    /** One parcel and its history, newest first. */
    public record ParcelDetail(DeliverySnapshot parcel, List<ParcelStatusEntry> history) {
    }
}
