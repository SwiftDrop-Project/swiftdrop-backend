package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ParcelStatusHistoryRepository extends JpaRepository<ParcelStatusHistory, UUID> {

    /** DeliveryApi.history(): one parcel's rows, newest first (uses idx_parcel_status_history_parcel_id). */
    List<ParcelStatusHistory> findByParcelIdOrderByOccurredAtDesc(UUID parcelId);

    /**
     * stats: per Sri Lanka day, how many parcels reached {@code toStatus} (a ParcelStatus name), at or after
     * {@code from} and before {@code to}. A parcel that fails twice on one day counts once for that day. Days with
     * none are missing; the caller fills them with 0.
     */
    @Query(value = "select to_char(occurred_at at time zone 'Asia/Colombo', 'YYYY-MM-DD') as day,"
            + " count(distinct parcel_id) as total from delivery.parcel_status_history"
            + " where to_status = :toStatus and occurred_at >= :from and occurred_at < :to group by 1",
            nativeQuery = true)
    List<DailyTotal> countReachedPerDay(String toStatus, Instant from, Instant to);
}
