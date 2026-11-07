package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.internal.domain.Parcel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    /** DeliveryApi.findByTrackingNumber. The caller has already normalised the number (no spaces, upper case). */
    Optional<Parcel> findByTrackingNumber(String trackingNumber);

    /** create(): a new tracking number must not be taken yet. */
    boolean existsByTrackingNumber(String trackingNumber);

    /** create(): a merchant's retry with the same order ref returns the first parcel instead of a new one. */
    Optional<Parcel> findByMerchantIdAndMerchantOrderRef(UUID merchantId, String merchantOrderRef);

    /**
     * findForCourier: the courier's unfinished parcels, and the finished ones whose last status change was at or
     * after {@code since}.
     */
    @Query("select p from Parcel p where p.assignedCourierId = :courierId"
            + " and (p.status not in (:finished) or p.statusChangedAt >= :since)")
    List<Parcel> findForCourier(UUID courierId, Set<ParcelStatus> finished, Instant since);

    /** findFinishedForCourier, newest status change first. */
    Page<Parcel> findByAssignedCourierIdAndStatusInOrderByStatusChangedAtDesc(
            UUID courierId, Set<ParcelStatus> statuses, Pageable pageable);

    /** The same, only the parcel with this tracking number. */
    Page<Parcel> findByAssignedCourierIdAndStatusInAndTrackingNumberOrderByStatusChangedAtDesc(
            UUID courierId, Set<ParcelStatus> statuses, String trackingNumber, Pageable pageable);
}
