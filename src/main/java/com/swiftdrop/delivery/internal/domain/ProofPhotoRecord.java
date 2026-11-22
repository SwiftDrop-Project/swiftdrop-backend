package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.ProofPhotoRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored proof-of-delivery photo (delivery.proof_photos): the durable reference to the image in file storage, never
 * a URL. It is uploaded for one parcel, attached when that parcel is marked DELIVERED, and never replaced after that.
 * The image (a signed delivery slip) is personal data; this row holds none: no toString needed beyond the default.
 */
@Entity
@Table(name = "proof_photos", schema = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProofPhotoRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    /** Which storage holds the image, e.g. CLOUDINARY or MEMORY. */
    @Column(nullable = false, length = 20)
    private String storage;

    /** The image's key in that storage (a Cloudinary public id). */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** When it was attached to the parcel's DELIVERED change; null while it is not attached. */
    @Column(name = "attached_at")
    private Instant attachedAt;

    @Transient
    private boolean isNew = true;

    /** A photo just stored for {@code parcelId}, not attached yet. */
    public static ProofPhotoRecord uploaded(UUID id, UUID parcelId, UUID uploadedBy, String storage, String storageKey,
                                            String contentType, long sizeBytes, String sha256, Instant uploadedAt) {
        ProofPhotoRecord record = new ProofPhotoRecord();
        record.id = id;
        record.parcelId = parcelId;
        record.uploadedBy = uploadedBy;
        record.storage = storage;
        record.storageKey = storageKey;
        record.contentType = contentType;
        record.sizeBytes = sizeBytes;
        record.sha256 = sha256;
        record.uploadedAt = uploadedAt;
        return record;
    }

    public boolean isAttached() {
        return attachedAt != null;
    }

    /** Attaches the photo to its parcel's DELIVERED change. The caller has checked it is this parcel's and free. */
    public void attach(Instant at) {
        if (attachedAt != null) {
            throw new IllegalStateException("A proof photo is attached only once");
        }
        this.attachedAt = at;
    }

    /** The public view for DeliveryApi.storeProofPhoto. */
    public ProofPhotoRef toRef() {
        return new ProofPhotoRef(id, contentType, sizeBytes, uploadedAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
