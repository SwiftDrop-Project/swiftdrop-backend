package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ProofPhoto;
import com.swiftdrop.delivery.ProofPhotoRef;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ProofPhotoRecord;
import com.swiftdrop.delivery.internal.persistence.ParcelRepository;
import com.swiftdrop.delivery.internal.persistence.ProofPhotoRepository;
import com.swiftdrop.delivery.internal.storage.ProofImageStorage;
import com.swiftdrop.delivery.internal.storage.ProofPhotoProperties;
import com.swiftdrop.delivery.internal.storage.ProofStorageException;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Proof-of-delivery photos (agreed with WP9): stores them in private file storage with a durable reference in
 * delivery.proof_photos, attaches one to its parcel's DELIVERED change, reads it back, and removes uploads that were
 * never attached. The image is a signed delivery slip (personal data): it is never logged, and neither is its key.
 */
@Component
class ProofPhotos {

    private static final Logger log = LoggerFactory.getLogger(ProofPhotos.class);

    /** Largest proof image accepted (the courier module checks the same limit first). */
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    static final String PHOTO_REQUIRED = "Add a photo of the signed delivery slip.";
    static final String PHOTO_AGAIN = "Add the photo again.";
    static final String PHOTO_ONLY_FOR_DELIVERED = "Only for delivered parcels.";

    private static final int SWEEP_BATCH = 50;

    private final ProofPhotoRepository photos;
    private final ParcelRepository parcels;
    private final ProofImageStorage storage;
    private final ProofPhotoProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    ProofPhotos(ProofPhotoRepository photos, ParcelRepository parcels, ProofImageStorage storage,
                ProofPhotoProperties properties, PlatformTransactionManager transactionManager) {
        this(photos, parcels, storage, properties, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    ProofPhotos(ProofPhotoRepository photos, ParcelRepository parcels, ProofImageStorage storage,
                ProofPhotoProperties properties, TransactionTemplate transactions, Clock clock) {
        this.photos = photos;
        this.parcels = parcels;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Stores a photo for the parcel; it is not attached until a DELIVERED change names it. The image goes to storage
     * first (outside any database transaction, so no connection is held during the upload), then its row is saved;
     * if the row can't be saved, the image is deleted again. Nothing is attached here, so a failure never changes the
     * parcel.
     *
     * @throws NotFoundException         NOT_FOUND for an unknown parcel
     * @throws FieldValidationException  field {@code file}: empty, too large or not a JPEG, PNG or WebP image
     * @throws BusinessRuleException     TASK_STEP_NOT_ALLOWED when the parcel is already delivered (its proof is
     *                                   final)
     * @throws ApiException              SERVICE_UNAVAILABLE when the storage can't be reached
     */
    ProofPhotoRef store(UUID parcelId, UUID uploadedBy, String contentType, byte[] content) {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        if (content == null || content.length == 0 || content.length > MAX_BYTES || contentType == null
                || !CONTENT_TYPES.contains(contentType)) {
            throw new FieldValidationException("file", "Use a JPEG, PNG or WebP photo up to 5 MB.");
        }
        Parcel parcel = parcels.findById(parcelId).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (parcel.getStatus() == ParcelStatus.DELIVERED) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        UUID id = UUID.randomUUID();
        String key;
        try {
            key = storage.put(id.toString(), content, contentType);
        } catch (ProofStorageException ex) {
            log.warn("Proof photo upload failed: {}", ex.getMessage());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            ProofPhotoRecord saved = photos.saveAndFlush(ProofPhotoRecord.uploaded(id, parcelId, uploadedBy,
                    storage.name(), key, contentType, content.length, sha256(content), clock.instant()));
            return saved.toRef();
        } catch (RuntimeException ex) {
            // The image is stored but has no row: delete it, so it doesn't stay behind unreferenced.
            deleteQuietly(key, contentType);
            throw ex;
        }
    }

    /**
     * Attaches the photo to the parcel's DELIVERED change. Runs inside that change's transaction (the photo row is
     * locked), so the attachment, the status, the history row and the outbox event are kept together or not at all.
     * The photo must be this parcel's, not attached yet, and (for a courier) uploaded by the same courier.
     *
     * @throws FieldValidationException field {@code proofPhotoId} otherwise
     */
    void attach(UUID parcelId, UUID photoId, UUID actorId, String actorRole, Instant at) {
        ProofPhotoRecord photo = photos.findByIdForUpdate(photoId)
                .orElseThrow(() -> new FieldValidationException("proofPhotoId", PHOTO_AGAIN));
        boolean someoneElses = "COURIER".equals(actorRole) && !photo.getUploadedBy().equals(actorId);
        if (!photo.getParcelId().equals(parcelId) || photo.isAttached() || someoneElses) {
            throw new FieldValidationException("proofPhotoId", PHOTO_AGAIN);
        }
        photo.attach(at);
        photos.save(photo);
    }

    /**
     * The parcel's attached photo, read from storage; empty when it has none.
     *
     * @throws ApiException SERVICE_UNAVAILABLE when the storage can't be reached
     */
    Optional<ProofPhoto> findAttached(UUID parcelId) {
        return photos.findByParcelIdAndAttachedAtIsNotNull(parcelId).map(photo -> {
            try {
                return new ProofPhoto(photo.getContentType(), storage.get(photo.getStorageKey(),
                        photo.getContentType()));
            } catch (ProofStorageException ex) {
                log.warn("Proof photo read failed: {}", ex.getMessage());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    /**
     * Removes photos uploaded longer ago than {@code orphanMaxAge} and never attached (the courier changed the photo,
     * or never confirmed the delivery). The row is deleted first, and only while it is still unattached, so an
     * attached proof is never removed; then its image. A failed image delete is logged and left behind.
     *
     * @return how many were removed
     */
    int removeOrphans() {
        Instant before = clock.instant().minus(properties.orphanMaxAge());
        List<ProofPhotoRecord> orphans = photos.findByAttachedAtIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(before,
                PageRequest.of(0, SWEEP_BATCH));
        int removed = 0;
        for (ProofPhotoRecord orphan : orphans) {
            Boolean deleted = transactions.execute(status -> photos.findByIdForUpdate(orphan.getId())
                    .filter(photo -> !photo.isAttached())
                    .map(photo -> {
                        photos.delete(photo);
                        return true;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(deleted)) {
                deleteQuietly(orphan.getStorageKey(), orphan.getContentType());
                removed++;
            }
        }
        return removed;
    }

    private void deleteQuietly(String key, String contentType) {
        try {
            storage.delete(key, contentType);
        } catch (ProofStorageException ex) {
            log.warn("Proof photo clean-up left an image behind: {}", ex.getMessage());
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }
}
