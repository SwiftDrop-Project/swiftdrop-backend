package com.swiftdrop.delivery;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored proof-of-delivery photo, returned by {@link DeliveryApi#storeProofPhoto}. Pass {@code photoId} as
 * {@link ChangeStatusCommand#proofPhotoId()} to attach it when the parcel is marked DELIVERED.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 *
 * @param photoId     id of the stored photo
 * @param contentType e.g. {@code image/jpeg}
 * @param sizeBytes   size of the stored image
 * @param uploadedAt  when it was stored
 */
public record ProofPhotoRef(UUID photoId, String contentType, long sizeBytes, Instant uploadedAt) {
}
