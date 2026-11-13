package com.swiftdrop.dispatch.internal.persistence;

import com.swiftdrop.dispatch.internal.domain.PickupQueueEntry;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Parcels waiting to be picked up, filled from ParcelStatusChanged events (see ParcelEventHandler). */
public interface PickupQueueRepository extends JpaRepository<PickupQueueEntry, UUID> {

    /** The district's waiting parcels, oldest first. Uses idx_pickup_queue_district. */
    List<PickupQueueEntry> findByPickupDistrictOrderByCreatedAtAsc(District pickupDistrict);

    long countByPickupDistrict(District pickupDistrict);

    /**
     * Adds the parcel unless it is already there. ON CONFLICT keeps this safe when the same event arrives twice or
     * two backends handle events at once.
     */
    @Modifying
    @Query(value = "INSERT INTO dispatch.pickup_queue"
            + " (parcel_id, tracking_number, pickup_district, parcel_version, created_at)"
            + " VALUES (:parcelId, :trackingNumber, :pickupDistrict, :parcelVersion, :createdAt)"
            + " ON CONFLICT (parcel_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(UUID parcelId, String trackingNumber, String pickupDistrict, long parcelVersion,
                       Instant createdAt);

    /** Removes the parcel, unless the row was written from a newer version than {@code parcelVersion}. */
    @Modifying
    @Query("delete from PickupQueueEntry e where e.parcelId = :parcelId and e.parcelVersion <= :parcelVersion")
    int deleteUpTo(UUID parcelId, long parcelVersion);
}
