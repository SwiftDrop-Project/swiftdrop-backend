package com.swiftdrop.reporting.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;

import java.util.Map;

/**
 * GET /admin/parcels/stats: the summary cards of the All Parcels page.
 *
 * @param pending   CREATED: booked, waiting to be picked up
 * @param inTransit PICKED_UP, AT_BRANCH, IN_TRANSIT and OUT_FOR_DELIVERY: on the way
 * @param failed    FAILED: a delivery attempt failed (the parcel goes back to a branch for another try)
 * @param byStatus  every status with its count, for anything the cards don't show
 */
public record AdminParcelStatsResponse(long total, long pending, long inTransit, long delivered, long failed,
                                       Map<ParcelStatus, Long> byStatus) {

    public AdminParcelStatsResponse {
        byStatus = Map.copyOf(byStatus);
    }
}
