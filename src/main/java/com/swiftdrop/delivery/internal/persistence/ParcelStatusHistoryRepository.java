package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParcelStatusHistoryRepository extends JpaRepository<ParcelStatusHistory, UUID> {

    /** DeliveryApi.history(): one parcel's rows, newest first (uses idx_parcel_status_history_parcel_id). */
    List<ParcelStatusHistory> findByParcelIdOrderByOccurredAtDesc(UUID parcelId);
}
