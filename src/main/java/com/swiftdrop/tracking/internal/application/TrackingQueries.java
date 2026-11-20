package com.swiftdrop.tracking.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.tracking.ParcelTracking;
import com.swiftdrop.tracking.TrackingApi;
import com.swiftdrop.tracking.internal.dto.IndexedParcelResponse;
import com.swiftdrop.tracking.internal.dto.TrackingPlace;
import com.swiftdrop.tracking.internal.dto.TrackingResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link TrackingApi} on top of the public lookup and the user's own parcels list. */
@Service
class TrackingQueries implements TrackingApi {

    private final TrackingService tracking;
    private final TrackingRateLimiter rateLimiter;
    private final MyParcels myParcels;

    TrackingQueries(TrackingService tracking, TrackingRateLimiter rateLimiter, MyParcels myParcels) {
        this.tracking = tracking;
        this.rateLimiter = rateLimiter;
        this.myParcels = myParcels;
    }

    @Override
    public Optional<ParcelTracking> find(String trackingNumber, String clientKey) {
        rateLimiter.check(clientKey);
        try {
            return Optional.of(toTracking(tracking.track(trackingNumber)));
        } catch (NotFoundException unknown) {
            return Optional.empty();
        }
    }

    @Override
    public List<ParcelTracking> mine(Owner owner, UUID userId, ParcelStatus status, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be 1-100");
        }
        MyParcels.Owner which = owner == Owner.MERCHANT ? MyParcels.Owner.MERCHANT : MyParcels.Owner.CUSTOMER;
        return myParcels.page(which, userId, status, 0, limit).content().stream()
                .map(TrackingQueries::toTracking)
                .toList();
    }

    private static ParcelTracking toTracking(TrackingResponse parcel) {
        return new ParcelTracking(parcel.trackingNumber(), parcel.status(), parcel.currentBranch(),
                place(parcel.from()), place(parcel.to()), parcel.createdAt(), parcel.updatedAt(),
                parcel.history().stream()
                        .map(step -> new ParcelTracking.Step(step.status(), step.branch(), step.failureReason(),
                                step.occurredAt()))
                        .toList());
    }

    private static ParcelTracking toTracking(IndexedParcelResponse parcel) {
        return new ParcelTracking(parcel.trackingNumber(), parcel.status(), parcel.currentBranch(),
                place(parcel.from()), place(parcel.to()), parcel.createdAt(), parcel.updatedAt(), List.of());
    }

    private static ParcelTracking.Place place(TrackingPlace place) {
        return place == null ? null : new ParcelTracking.Place(place.city(), place.district());
    }
}
