package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.delivery.internal.domain.ProofPhotoRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProofPhotoRepository extends JpaRepository<ProofPhotoRecord, UUID> {

    /** The photo to attach, locked so two DELIVERED changes can't attach it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProofPhotoRecord p where p.id = :id")
    Optional<ProofPhotoRecord> findByIdForUpdate(UUID id);

    /** The parcel's attached photo (at most one: uk_proof_photos_one_attached_per_parcel). */
    Optional<ProofPhotoRecord> findByParcelIdAndAttachedAtIsNotNull(UUID parcelId);

    /** Photos uploaded before {@code before} and never attached, oldest first (idx_proof_photos_unattached). */
    List<ProofPhotoRecord> findByAttachedAtIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(Instant before,
                                                                                         Pageable page);
}
