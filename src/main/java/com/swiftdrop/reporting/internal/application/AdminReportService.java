package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.DayCount;
import com.swiftdrop.reporting.internal.dto.ReportSummaryResponse;
import com.swiftdrop.reporting.internal.dto.ReportSummaryResponse.Totals;
import com.swiftdrop.shared.error.FieldValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The admin's Reports &amp; Analytics page: delivery volume for a range of Sri Lanka days, the same number of days
 * before it, and parcels by status now. Reporting owns no tables: it reads through {@link DeliveryApi} only.
 * Delivery treats a bad range as a programming error, so this service checks the admin's dates first and answers 400.
 */
@Service
public class AdminReportService {

    /** Report days are Sri Lanka days, the same as {@link DeliveryApi#stats}. */
    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    /** The longest range, the same as {@link DeliveryApi#stats} allows. */
    static final int MAX_DAYS = 366;

    private final DeliveryApi deliveries;
    private final Clock clock;

    @Autowired
    public AdminReportService(DeliveryApi deliveries) {
        this(deliveries, Clock.system(SRI_LANKA));
    }

    AdminReportService(DeliveryApi deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    /**
     * The report for {@code from} to {@code to}, both included.
     *
     * @throws FieldValidationException field {@code from} or {@code to} when the range is backwards, ends in the
     *                                  future or is longer than 366 days
     */
    public ReportSummaryResponse summary(LocalDate from, LocalDate to) {
        checkRange(from, to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);

        DeliveryStats stats = deliveries.stats(from, to);
        DeliveryStats before = deliveries.stats(previousFrom, previousTo);

        List<DayCount> perDay = stats.perDay().stream()
                .map(day -> new DayCount(day.date(), day.created(), day.delivered(), day.failed()))
                .toList();
        return new ReportSummaryResponse(totals(stats), totals(before), perDay, byStatus());
    }

    /** The range's days as CSV: a header, one row per day (oldest first) and a total row. */
    public String summaryCsv(LocalDate from, LocalDate to) {
        ReportSummaryResponse report = summary(from, to);
        StringBuilder csv = new StringBuilder("date,booked,delivered,failed\r\n");
        for (DayCount day : report.perDay()) {
            csv.append(day.date()).append(',').append(day.created()).append(',').append(day.delivered()).append(',')
                    .append(day.failed()).append("\r\n");
        }
        Totals totals = report.totals();
        csv.append("total,").append(totals.created()).append(',').append(totals.delivered()).append(',')
                .append(totals.failed()).append("\r\n");
        return csv.toString();
    }

    private void checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new FieldValidationException("from", "The start date must be on or before the end date.");
        }
        if (to.isAfter(LocalDate.now(clock.withZone(SRI_LANKA)))) {
            throw new FieldValidationException("to", "The end date can't be in the future.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new FieldValidationException("to", "Choose a range of up to " + MAX_DAYS + " days.");
        }
    }

    private static Totals totals(DeliveryStats stats) {
        return new Totals(stats.from(), stats.to(), stats.totalCreated(), stats.totalDelivered(),
                stats.totalFailed(), AdminOverviewService.successRate(stats.totalDelivered(), stats.totalFailed()));
    }

    /** Every status, with 0 for the ones no parcel is in. */
    private Map<ParcelStatus, Long> byStatus() {
        Map<ParcelStatus, Long> counts = new EnumMap<>(ParcelStatus.class);
        for (ParcelStatus status : ParcelStatus.values()) {
            counts.put(status, 0L);
        }
        counts.putAll(deliveries.countByStatus());
        return counts;
    }
}
