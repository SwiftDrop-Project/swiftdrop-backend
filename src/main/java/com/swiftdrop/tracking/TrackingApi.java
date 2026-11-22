package com.swiftdrop.tracking;

import com.swiftdrop.delivery.ParcelStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the tracking module: the same public view the tracking page shows, a user's own
 * parcels, and counts for the admin reports. Never names, phones, addresses, notes or amounts.
 *
 * <p>Contract owned by tracking.
 */
public interface TrackingApi {

    /** Whose parcels {@link #mine} lists: a customer's bookings or a merchant's orders. */
    enum Owner { CUSTOMER, MERCHANT }

    /**
     * The public view of a parcel, as {@code GET /track/{trackingNumber}} shows it. Spaces and letter case are ignored.
     * Counts as one lookup for {@code clientKey} (an IP address or user id), like the public endpoint.
     *
     * @return empty for an unknown number or one in the wrong format
     * @throws com.swiftdrop.shared.error.ApiException TOO_MANY_REQUESTS when the client is over the lookup limit
     */
    Optional<ParcelTracking> find(String trackingNumber, String clientKey);

    /**
     * The owner's parcels, newest first, without their steps.
     *
     * @param status optional: only parcels with this status
     * @param limit  1-100
     */
    List<ParcelTracking> mine(Owner owner, UUID userId, ParcelStatus status, int limit);

    /**
     * Parcels booked between two Sri Lanka days (both included) by pickup and delivery district, and failed deliveries
     * by reason, from tracking's own index (filled from parcel events and lookups). For the admin reports; callers
     * check that the user is an admin.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from} or the range is longer than 366 days
     */
    ParcelBreakdown breakdown(LocalDate from, LocalDate to);
}
