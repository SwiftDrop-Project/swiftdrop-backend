package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.shared.reference.District;
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

    /**
     * findAtBranch: parcels waiting at this branch, oldest status change first. AT_BRANCH is written into the query
     * (not passed as a parameter), so PostgreSQL can always use the partial index idx_parcels_at_branch.
     */
    @Query("select p from Parcel p where p.status = com.swiftdrop.delivery.ParcelStatus.AT_BRANCH"
            + " and p.currentBranch = :branch order by p.statusChangedAt")
    List<Parcel> findWaitingAtBranch(District branch);

    /** The same, only the parcel with this tracking number. */
    Page<Parcel> findByAssignedCourierIdAndStatusInAndTrackingNumberOrderByStatusChangedAtDesc(
            UUID courierId, Set<ParcelStatus> statuses, String trackingNumber, Pageable pageable);

    /**
     * findByCustomer, newest first. The id breaks ties between parcels booked at the same moment, so paging is
     * stable (no parcel shown twice or skipped). Uses idx_parcels_customer_id.
     */
    Page<Parcel> findByCustomerIdOrderByCreatedAtDescIdDesc(UUID customerId, Pageable pageable);

    /** The same, only parcels in this status. */
    Page<Parcel> findByCustomerIdAndStatusOrderByCreatedAtDescIdDesc(UUID customerId, ParcelStatus status,
                                                                     Pageable pageable);

    /** findByMerchant, newest first, with the same tie-break. Uses idx_parcels_merchant_id. */
    Page<Parcel> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Pageable pageable);

    /** The same, only orders in this status. */
    Page<Parcel> findByMerchantIdAndStatusOrderByCreatedAtDescIdDesc(UUID merchantId, ParcelStatus status,
                                                                     Pageable pageable);

    /**
     * stats: parcels booked per Sri Lanka day, booked at or after {@code from} and before {@code to}. Days with no
     * parcels are missing; the caller fills them with 0. Uses idx_parcels_created_at.
     */
    @Query(value = "select to_char(created_at at time zone 'Asia/Colombo', 'YYYY-MM-DD') as day, count(*) as total"
            + " from delivery.parcels where created_at >= :from and created_at < :to group by 1",
            nativeQuery = true)
    List<DailyTotal> countCreatedPerDay(Instant from, Instant to);
}
