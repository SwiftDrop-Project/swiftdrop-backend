package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;
import java.util.Map;

/**
 * How many parcels match the other filters, per status ({@code GET /admin/tracking/parcels/counts}).
 *
 * @param byStatus every status, 0 when none
 */
public record ParcelStatusCountsResponse(long total, Map<ParcelStatus, Long> byStatus) {
}
