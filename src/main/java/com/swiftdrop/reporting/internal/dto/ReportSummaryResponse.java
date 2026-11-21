package com.swiftdrop.reporting.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.DayCount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Body of GET /admin/reports/summary: the Reports &amp; Analytics page for a range of Sri Lanka days (API.md "Reports
 * (admin, WP4)").
 *
 * @param totals   the range asked for
 * @param previous the same number of days just before it, to compare with
 * @param perDay   every day of the range, oldest first, also days with nothing
 * @param byStatus how many parcels are in each status right now (every status, not limited to the range)
 */
public record ReportSummaryResponse(Totals totals, Totals previous, List<DayCount> perDay,
                                    Map<ParcelStatus, Long> byStatus) {

    public ReportSummaryResponse {
        perDay = List.copyOf(perDay);
        byStatus = Map.copyOf(byStatus);
    }

    /**
     * Totals for a range of days, both included.
     *
     * @param created     parcels booked in the range
     * @param delivered   parcels that reached DELIVERED in the range
     * @param failed      parcels that reached FAILED in the range
     * @param successRate delivered / (delivered + failed) as a percentage with one decimal; 0.0 when both are 0
     */
    public record Totals(LocalDate from, LocalDate to, long created, long delivered, long failed,
                         BigDecimal successRate) {
    }
}
