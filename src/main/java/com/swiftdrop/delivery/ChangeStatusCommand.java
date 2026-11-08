package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.util.Objects;
import java.util.UUID;

/**
 * Input for moving a parcel to another status ({@link DeliveryApi#changeStatus}).
 *
 * <p>The caller has already checked which fields are present for the target status: {@code failureReason} only
 * for FAILED (and a {@code note} when it is OTHER); {@code recipientName} and {@code proofPhotoId} only for
 * DELIVERED, at least one of them. Delivery checks the transition itself and that {@code proofPhotoId} belongs to
 * this parcel. The branch rule is checked here, because a wrong branch would break the parcel's location.
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
 * @param branch         the branch the parcel arrives at: required for AT_BRANCH, null for every other status
 */
public record ChangeStatusCommand(
        UUID parcelId,
        ParcelStatus toStatus,
        UUID actorId,
        String actorRole,
        String note,
        FailureReason failureReason,
        String recipientName,
        UUID proofPhotoId,
        District branch) {

    /**
     * The parcel, the new status and who makes the change are always needed: a missing one is a caller's bug. So is
     * a branch that does not match the status.
     *
     * @throws NullPointerException     when parcelId, toStatus, actorId or actorRole is null
     * @throws IllegalArgumentException when branch is null for AT_BRANCH, or set for another status
     */
    public ChangeStatusCommand {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole");
        if (toStatus == ParcelStatus.AT_BRANCH && branch == null) {
            throw new IllegalArgumentException("branch is required for AT_BRANCH");
        }
        if (toStatus != ParcelStatus.AT_BRANCH && branch != null) {
            throw new IllegalArgumentException("branch is only for AT_BRANCH");
        }
    }

    /** A change to any status except AT_BRANCH (no branch). Kept so callers written before branches still compile. */
    public ChangeStatusCommand(UUID parcelId, ParcelStatus toStatus, UUID actorId, String actorRole, String note,
                               FailureReason failureReason, String recipientName, UUID proofPhotoId) {
        this(parcelId, toStatus, actorId, actorRole, note, failureReason, recipientName, proofPhotoId, null);
    }
}
