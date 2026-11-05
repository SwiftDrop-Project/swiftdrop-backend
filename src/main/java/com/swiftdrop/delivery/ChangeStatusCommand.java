package com.swiftdrop.delivery;

import java.util.Objects;
import java.util.UUID;

/**
 * Input for moving a parcel to another status ({@link DeliveryApi#changeStatus}).
 *
 * <p>The caller has already checked which fields are present for the target status: {@code failureReason} only
 * for FAILED (and a {@code note} when it is OTHER); {@code recipientName} and {@code proofPhotoId} only for
 * DELIVERED, at least one of them. Delivery checks the transition itself and that {@code proofPhotoId} belongs to
 * this parcel.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 *
 * @param parcelId       the parcel to change
 * @param toStatus       the new status; must be allowed by {@link ParcelStatus#canMoveTo}
 * @param actorId        identity user id of who made the change
 * @param actorRole      role name of who made the change, e.g. {@code COURIER} (a String, so delivery never
 *                       depends on identity; same as {@link ParcelStatusChanged#actorRole()})
 * @param note           optional free text, max 500 characters
 * @param failureReason  only for FAILED
 * @param recipientName  only for DELIVERED: who received the parcel
 * @param proofPhotoId   only for DELIVERED: a photo returned by {@link DeliveryApi#storeProofPhoto} for this parcel
 */
public record ChangeStatusCommand(
        UUID parcelId,
        ParcelStatus toStatus,
        UUID actorId,
        String actorRole,
        String note,
        FailureReason failureReason,
        String recipientName,
        UUID proofPhotoId) {

    /** The parcel, the new status and who makes the change are always needed: a missing one is a caller's bug. */
    public ChangeStatusCommand {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole");
    }
}
