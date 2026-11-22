package com.swiftdrop.tracking.internal.persistence;

import com.swiftdrop.tracking.internal.domain.ParcelIndexEntry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ParcelIndexRepository
        extends JpaRepository<ParcelIndexEntry, UUID>, JpaSpecificationExecutor<ParcelIndexEntry> {

    /**
     * Adds the parcel, or updates its row, unless the row was written from a newer (or the same) version. Safe when
     * two events for one parcel are handled at once.
     */
    @Modifying
    @Query(value = """
            INSERT INTO tracking.parcel_index (parcel_id, tracking_number, status, origin_city, origin_district,
                destination_city, destination_district, current_branch, failure_reason, created_at, updated_at,
                parcel_version)
            VALUES (:parcelId, :trackingNumber, :status, :originCity, :originDistrict, :destinationCity,
                :destinationDistrict, :currentBranch, :failureReason, :createdAt, :updatedAt, :parcelVersion)
            ON CONFLICT (parcel_id) DO UPDATE SET
                status = EXCLUDED.status,
                origin_city = EXCLUDED.origin_city,
                origin_district = EXCLUDED.origin_district,
                destination_city = EXCLUDED.destination_city,
                destination_district = EXCLUDED.destination_district,
                current_branch = EXCLUDED.current_branch,
                failure_reason = EXCLUDED.failure_reason,
                updated_at = EXCLUDED.updated_at,
                parcel_version = EXCLUDED.parcel_version
            WHERE tracking.parcel_index.parcel_version < EXCLUDED.parcel_version
            """, nativeQuery = true)
    int upsert(UUID parcelId, String trackingNumber, String status, String originCity, String originDistrict,
               String destinationCity, String destinationDistrict, String currentBranch, String failureReason,
               Instant createdAt, Instant updatedAt, long parcelVersion);
}
