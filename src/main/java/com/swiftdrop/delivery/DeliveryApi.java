package com.swiftdrop.delivery;

import com.swiftdrop.shared.web.PageResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the delivery module: create parcels, change their status, assign couriers and read
 * parcels. The only way into the delivery module from outside it.
 *
 * <p>Reads do not check who is asking: the calling module knows the logged-in user and must check ownership itself,
 * using {@code customerId}, {@code merchantId} or {@code courierId} in the snapshot. Writes check their own rules and
 * throw {@code shared.error} exceptions, which reach the client in the API.md error shape.
 */
public interface DeliveryApi {

    /**
     * Creates a parcel in {@link ParcelStatus#CREATED}. Asks pricing for the fee, then saves the parcel, its first
     * history entry and a {@link ParcelStatusChanged} event in one transaction. If the merchant already sent an order
     * with the same {@code merchantOrderRef}, that parcel is returned and nothing new is created, so a merchant can
     * safely retry. Errors from pricing are passed on unchanged.
     */
    DeliverySnapshot create(CreateDeliveryCommand command);

    /**
     * Moves a parcel to {@code command.toStatus()}, adds a history entry and saves a {@link ParcelStatusChanged}
     * event, all in one transaction.
     *
     * <p>Errors: 404 NOT_FOUND (unknown parcel); 403 FORBIDDEN (a courier changing a parcel that is not assigned to
     * them, or a customer or merchant changing a status); 409 INVALID_STATUS_TRANSITION (the move is not allowed, see
     * {@link ParcelStatus#canMoveTo}); 400 FAILURE_REASON_REQUIRED (FAILED without a reason).
     */
    DeliverySnapshot changeStatus(ChangeStatusCommand command);

    /**
     * Assigns a courier to a parcel that is still {@link ParcelStatus#CREATED}; calling it again replaces the courier.
     * The status does not change, so no history entry or event is written. The caller (dispatch) must first check
     * that {@code courierId} is an active courier, because delivery does not depend on identity.
     *
     * <p>Errors: 404 NOT_FOUND (unknown parcel); 409 PARCEL_NOT_ASSIGNABLE (already picked up or finished).
     */
    DeliverySnapshot assignCourier(UUID parcelId, UUID courierId);

    /** Empty when no parcel has this id. */
    Optional<DeliverySnapshot> findById(UUID parcelId);

    /** Ignores case and surrounding spaces. Empty when no parcel has this tracking number. */
    Optional<DeliverySnapshot> findByTrackingNumber(String trackingNumber);

    /**
     * A customer's parcels, newest first. {@code status} null means every status; {@code page} is 0-based and
     * {@code size} is capped at 100.
     */
    PageResponse<DeliverySnapshot> findByCustomer(UUID customerId, ParcelStatus status, int page, int size);

    /** A merchant's orders, newest first. Same filter and paging rules as {@link #findByCustomer}. */
    PageResponse<DeliverySnapshot> findByMerchant(UUID merchantId, ParcelStatus status, int page, int size);

    /** Parcels assigned to a courier, newest first (the courier's history). Same rules as {@link #findByCustomer}. */
    PageResponse<DeliverySnapshot> findByCourier(UUID courierId, ParcelStatus status, int page, int size);

    /** A courier's parcels that are not finished (not DELIVERED or FAILED), oldest first: the day's work. */
    List<DeliverySnapshot> findActiveByCourier(UUID courierId);

    /** Parcels in {@link ParcelStatus#CREATED} with no courier, oldest first, for the dispatcher to assign. */
    List<DeliverySnapshot> findUnassigned();

    /**
     * The number of unfinished parcels per courier id (courier workloads). Couriers with none are not in the map,
     * so read it with {@code getOrDefault(courierId, 0L)}.
     */
    Map<UUID, Long> countActiveByCourier();

    /** A parcel's status timeline, oldest entry first. Empty for an unknown parcel. */
    List<StatusHistoryEntry> history(UUID parcelId);

    /**
     * Parcel counts per Sri Lanka day from {@code from} to {@code to}, both included, for the reports.
     * {@code from} must not be after {@code to} and the range is at most 366 days. The reporting module checks the
     * admin's dates and answers 400, so a bad range here is a programming error (IllegalArgumentException).
     */
    DeliveryStats stats(LocalDate from, LocalDate to);
}
