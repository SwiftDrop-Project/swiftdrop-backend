package com.swiftdrop.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the delivery module (create parcels, change status, read parcels).
 *
 * <p>Contract owned by delivery. The methods below were agreed with WP9 (courier); the implementation is pending
 * (WP3). Creating parcels and assigning couriers (for dispatch) are still to be added by their owners.
 */
public interface DeliveryApi {

    /** The parcel, or empty for an unknown id. */
    Optional<DeliverySnapshot> findById(UUID parcelId);

    /**
     * Parcels assigned to the courier that are not finished (not DELIVERED or FAILED), plus the finished ones whose
     * last status change happened at or after {@code finishedSince}. Any order.
     */
    List<DeliverySnapshot> findForCourier(UUID courierId, Instant finishedSince);

    /**
     * DELIVERED and FAILED parcels assigned to the courier, newest status change first.
     *
     * @param trackingNumber optional; when not null, only the parcel with exactly this normalised tracking number
     *                       (no spaces, upper case, e.g. {@code SD2611004817})
     */
    Page<DeliverySnapshot> findFinishedForCourier(UUID courierId, String trackingNumber, Pageable pageable);

    /** The parcel's status history, newest first. Empty for an unknown id. */
    List<ParcelStatusEntry> history(UUID parcelId);

    /**
     * Moves the parcel to {@code command.toStatus()}, records the change in its history (and the proof or failure
     * reason) and writes {@link ParcelStatusChanged} to the outbox, all in one transaction.
     *
     * <p>Which fields are present for the target status is checked by the caller (see {@link ChangeStatusCommand}).
     *
     * @return the parcel after the change
     * @throws com.swiftdrop.shared.error.NotFoundException          NOT_FOUND for an unknown parcel
     * @throws com.swiftdrop.shared.error.BusinessRuleException      INVALID_STATUS_TRANSITION when
     *                                                               {@link ParcelStatus#canMoveTo} is false for the
     *                                                               parcel's current status
     * @throws com.swiftdrop.shared.error.ForbiddenException         FORBIDDEN when {@code actorRole} is COURIER and
     *                                                               {@code actorId} is not the assigned courier
     * @throws com.swiftdrop.shared.error.FieldValidationException   field {@code proofPhotoId} when the photo was not
     *                                                               stored for this parcel
     */
    DeliverySnapshot changeStatus(ChangeStatusCommand command);

    /**
     * Stores a proof-of-delivery photo for the parcel. It is not attached until a DELIVERED change names it in
     * {@link ChangeStatusCommand#proofPhotoId()}. The caller has already checked the image type and size.
     *
     * @throws com.swiftdrop.shared.error.NotFoundException NOT_FOUND for an unknown parcel
     */
    ProofPhotoRef storeProofPhoto(UUID parcelId, UUID uploadedBy, String contentType, byte[] content);

    /** The photo attached to the parcel's proof of delivery, or empty when there is none. */
    Optional<ProofPhoto> findProofPhoto(UUID parcelId);
}
