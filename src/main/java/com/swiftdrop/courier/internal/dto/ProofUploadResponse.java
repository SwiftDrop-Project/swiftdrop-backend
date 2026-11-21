package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.delivery.ProofPhotoRef;

import java.time.Instant;
import java.util.UUID;

/**
 * Answer of {@code POST /courier/deliveries/{parcelId}/proof}: the stored photo. Send {@code proofPhotoId} with the
 * DELIVERED status change to attach it. No URL: the image is private.
 *
 * @param contentType {@code image/jpeg}, {@code image/png} or {@code image/webp}
 * @param sizeBytes   size as stored (metadata removed)
 */
public record ProofUploadResponse(UUID proofPhotoId, String contentType, long sizeBytes, Instant uploadedAt) {

    public static ProofUploadResponse from(ProofPhotoRef ref) {
        return new ProofUploadResponse(ref.photoId(), ref.contentType(), ref.sizeBytes(), ref.uploadedAt());
    }
}
