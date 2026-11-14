package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.TrackingPlace;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The parcels a customer booked or a merchant created, newest first, as the starting point for their private live
 * updates on {@code /user/queue/parcels}. The owner is always the logged-in user; it never comes from the request.
 */
@Service
public class MyParcels {

    public enum Owner { CUSTOMER, MERCHANT }

    private final DeliveryApi deliveries;

    public MyParcels(DeliveryApi deliveries) {
        this.deliveries = deliveries;
    }

    public PageResponse<IndexedParcelResponse> page(Owner owner, UUID userId, ParcelStatus status, int page,
                                                    int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<DeliverySnapshot> parcels = owner == Owner.CUSTOMER
                ? deliveries.findByCustomer(userId, status, request)
                : deliveries.findByMerchant(userId, status, request);
        return PageResponse.from(parcels, MyParcels::toResponse);
    }

    static IndexedParcelResponse toResponse(DeliverySnapshot parcel) {
        return new IndexedParcelResponse(
                parcel.id(),
                parcel.trackingNumber(),
                parcel.status(),
                place(parcel.pickup()),
                place(parcel.recipient()),
                parcel.status() == ParcelStatus.AT_BRANCH ? parcel.currentBranch() : null,
                parcel.status() == ParcelStatus.FAILED ? parcel.failureReason() : null,
                parcel.createdAt(),
                parcel.updatedAt(),
                parcel.version());
    }

    private static TrackingPlace place(DeliveryContact contact) {
        return contact == null ? null : new TrackingPlace(contact.city(), contact.district());
    }
}
