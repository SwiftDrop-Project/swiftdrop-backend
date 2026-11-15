package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the delivery module: create parcels, assign couriers, change status, store proof
 * photos, read parcels and count them for reports. The only way into the delivery module from outside it.
 *
 * <p>Reads do not check who is asking: the calling module knows the logged-in user and must check ownership itself,
 * using {@code customerId} or {@code merchantId} in the snapshot, or (for couriers) the dispatch assignments. Writes
 * check their own rules and throw {@code shared.error} exceptions, which reach the client in the API.md error shape.
 *
 * <p>Multi-branch workflow: one parcel is handled by several couriers (pickup, transfer between branches, final
 * delivery), and who handles which step is decided by dispatch (WP8) assignments, not by this module. The
 * single-courier members ({@link #assignCourier}, {@link #findUnassigned}, {@link #countActiveByCourier},
 * {@link #findForCourier}, {@link #findFinishedForCourier}, and {@code assignedCourierId} and {@code assignedAt} in
 * {@link DeliverySnapshot}) are deprecated and will not be built.
 *
 * <p>Contract owned by delivery (WP3); the courier methods were agreed with WP9 (courier).
 */
public interface DeliveryApi {

    // ---- Creating parcels and assigning couriers ----

    /**
     * Creates a parcel in {@link ParcelStatus#CREATED}. Asks pricing for the fee, then saves the parcel, its first
     * history entry and a {@link ParcelStatusChanged} event in one transaction. If the merchant already sent an order
     * with the same {@code merchantOrderRef}, that parcel is returned and nothing new is created, so a merchant can
     * safely retry. Errors from pricing are passed on unchanged.
     *
     * @throws com.swiftdrop.shared.error.FieldValidationException field {@code lengthCm} when the command has no
     *                                                             {@link CreateDeliveryCommand#size()}
     * @throws com.swiftdrop.shared.error.BusinessRuleException    PARCEL_NEEDS_MANUAL_QUOTE when no vehicle carries
     *                                                             or fits the parcel
     */
    DeliverySnapshot create(CreateDeliveryCommand command);

    /**
     * Assigns a courier to a parcel that is still {@link ParcelStatus#CREATED} (sets {@code assignedCourierId} and
     * {@code assignedAt}); calling it again replaces the courier. The status does not change, so no history entry or
     * event is written. The caller (dispatch) must first check that {@code courierId} is an active courier, because
     * delivery does not depend on identity.
     *
     * @throws com.swiftdrop.shared.error.NotFoundException     NOT_FOUND for an unknown parcel
     * @throws com.swiftdrop.shared.error.BusinessRuleException PARCEL_NOT_ASSIGNABLE when the parcel was already
     *                                                          picked up or is finished
     * @deprecated one courier no longer owns a parcel from pickup to delivery; dispatch (WP8) assignments say who
     *             handles each step. Will not be built.
     */
    @Deprecated
    DeliverySnapshot assignCourier(UUID parcelId, UUID courierId);

    // ---- Reading parcels ----

    /** The parcel, or empty for an unknown id. */
    Optional<DeliverySnapshot> findById(UUID parcelId);

    /**
     * The parcel with this tracking number, or empty. The number is normalised first (spaces removed, upper case), so
     * {@code " sd 2611 004817 "} finds {@code SD2611004817}.
     */
    Optional<DeliverySnapshot> findByTrackingNumber(String trackingNumber);

    /** A customer's parcels, newest first. {@code status} null means every status; the page size is capped at 100. */
    Page<DeliverySnapshot> findByCustomer(UUID customerId, ParcelStatus status, Pageable pageable);

    /** A merchant's orders, newest first. Same filter and paging rules as {@link #findByCustomer}. */
    Page<DeliverySnapshot> findByMerchant(UUID merchantId, ParcelStatus status, Pageable pageable);

    /**
     * The parcels with these ids, in any order. Unknown ids are left out, so the result may be shorter than the
     * input. One call instead of one {@link #findById} per parcel (courier assignment lists).
     */
    List<DeliverySnapshot> findAllById(Collection<UUID> parcelIds);

    /**
     * Parcels in {@link ParcelStatus#AT_BRANCH} at this branch, oldest status change first, for dispatch (WP8) to
     * plan the next step.
     */
    List<DeliverySnapshot> findAtBranch(District branch);

    /**
     * Parcels in {@link ParcelStatus#CREATED} with no courier, oldest first, for the dispatcher to assign.
     *
     * @deprecated dispatch (WP8) assignments replace the single assigned courier. Will not be built.
     */
    @Deprecated
    List<DeliverySnapshot> findUnassigned();

    /**
     * The number of unfinished parcels per courier id (courier workloads). Couriers with none are not in the map,
     * so read it with {@code getOrDefault(courierId, 0L)}.
     *
     * @deprecated courier workloads come from dispatch (WP8) assignments. Will not be built.
     */
    @Deprecated
    Map<UUID, Long> countActiveByCourier();

    /**
     * Parcels assigned to the courier that are not finished, plus the finished ones whose last status change
     * happened at or after {@code finishedSince}. Any order.
     *
     * @deprecated a courier's parcels come from their dispatch (WP8) assignments. Will not be built.
     */
    @Deprecated
    List<DeliverySnapshot> findForCourier(UUID courierId, Instant finishedSince);

    /**
     * Finished parcels assigned to the courier, newest status change first.
     *
     * @param trackingNumber optional; when not null, only the parcel with exactly this normalised tracking number
     *                       (no spaces, upper case, e.g. {@code SD2611004817})
     * @deprecated a courier's history comes from their dispatch (WP8) assignments. Will not be built.
     */
    @Deprecated
    Page<DeliverySnapshot> findFinishedForCourier(UUID courierId, String trackingNumber, Pageable pageable);

    /**
     * Every parcel matching {@code search}, in its {@link ParcelSort} order, for the admin's All Parcels page (WP4).
     * The sort in {@code pageable} is ignored and the page size is capped at 100. Like every read here, it does not
     * check who is asking: the caller must allow only admins.
     */
    Page<DeliverySnapshot> search(ParcelSearch search, Pageable pageable);

    /** How many parcels are in each status right now. Every status is in the map, with 0 when there are none. */
    Map<ParcelStatus, Long> countByStatus();

    /** The parcel's status history, newest first. Empty for an unknown id. */
    List<ParcelStatusEntry> history(UUID parcelId);

    // ---- Status changes and proof of delivery (agreed with WP9) ----

    /**
     * Moves the parcel to {@code command.toStatus()}, records the change in its history (and the proof or failure
     * reason) and writes {@link ParcelStatusChanged} to the outbox, all in one transaction.
     *
     * <p>Which fields are present for the target status is checked by the caller (see {@link ChangeStatusCommand}).
     * Moving to AT_BRANCH sets {@link DeliverySnapshot#currentBranch()} to {@code command.branch()}; any other move
     * clears it. Leaving FAILED clears the failure reason (it stays in the history).
     *
     * <p>The caller authorizes the change: delivery does not check which courier makes it (the courier module checks
     * the courier's dispatch assignment first). Delivery only checks the role.
     *
     * @return the parcel after the change
     * @throws com.swiftdrop.shared.error.NotFoundException          NOT_FOUND for an unknown parcel
     * @throws com.swiftdrop.shared.error.BusinessRuleException      INVALID_STATUS_TRANSITION when
     *                                                               {@link ParcelStatus#canMoveTo} is false for the
     *                                                               parcel's current status
     * @throws com.swiftdrop.shared.error.ForbiddenException         FORBIDDEN when {@code actorRole} is neither
     *                                                               COURIER nor ADMIN
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

    // ---- Reports (for WP4) ----

    /**
     * Parcel counts per Sri Lanka day from {@code from} to {@code to}, both included, for the reports.
     * {@code from} must not be after {@code to} and the range is at most 366 days. The reporting module checks the
     * admin's dates and answers 400, so a bad range here is a programming error (IllegalArgumentException).
     */
    DeliveryStats stats(LocalDate from, LocalDate to);
}
