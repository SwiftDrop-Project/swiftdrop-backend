package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.DeliveryStats.DailyCount;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.reporting.internal.dto.ReportSummaryResponse;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Reports &amp; Analytics summary, with delivery mocked and "today" fixed at 20 Nov 2026 in Sri Lanka. */
class AdminReportServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    // 10:00 in Colombo on 20 Nov.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T04:30:00Z"), AdminReportService.SRI_LANKA);

    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final AdminReportService service = new AdminReportService(deliveries, CLOCK);

    @Test
    void comparesWithTheSameNumberOfDaysJustBefore() {
        LocalDate from = LocalDate.of(2026, 11, 18);
        when(deliveries.stats(from, TODAY)).thenReturn(stats(from, TODAY, new long[][] {{5, 3, 1}, {2, 4, 0}, {6, 1, 1}}));
        LocalDate before = LocalDate.of(2026, 11, 15);
        LocalDate beforeTo = LocalDate.of(2026, 11, 17);
        when(deliveries.stats(before, beforeTo))
                .thenReturn(stats(before, beforeTo, new long[][] {{1, 1, 0}, {1, 1, 0}, {1, 0, 1}}));
        when(deliveries.countByStatus()).thenReturn(Map.of(ParcelStatus.CREATED, 7L, ParcelStatus.DELIVERED, 20L));

        ReportSummaryResponse report = service.summary(from, TODAY);

        assertThat(report.totals().from()).isEqualTo(from);
        assertThat(report.totals().created()).isEqualTo(13);
        assertThat(report.totals().delivered()).isEqualTo(8);
        assertThat(report.totals().failed()).isEqualTo(2);
        assertThat(report.totals().successRate()).isEqualByComparingTo("80.0");
        assertThat(report.previous().from()).isEqualTo(before);
        assertThat(report.previous().to()).isEqualTo(beforeTo);
        assertThat(report.previous().successRate()).isEqualByComparingTo("66.7");
        assertThat(report.perDay()).hasSize(3);
        assertThat(report.perDay().getFirst().date()).isEqualTo(from);
        assertThat(report.perDay().getFirst().created()).isEqualTo(5);
    }

    @Test
    void everyStatusIsInTheBreakdownWithZeroForTheEmptyOnes() {
        givenAnyStats();
        when(deliveries.countByStatus()).thenReturn(Map.of(ParcelStatus.IN_TRANSIT, 4L));

        ReportSummaryResponse report = service.summary(TODAY, TODAY);

        assertThat(report.byStatus()).hasSize(ParcelStatus.values().length);
        assertThat(report.byStatus().get(ParcelStatus.IN_TRANSIT)).isEqualTo(4L);
        assertThat(report.byStatus().get(ParcelStatus.FAILED)).isZero();
    }

    @Test
    void aBadRangeIsAFieldErrorAndNeverReachesDelivery() {
        assertThatThrownBy(() -> service.summary(TODAY, TODAY.minusDays(1)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.fieldErrors()).containsEntry("from",
                                "The start date must be on or before the end date."));
        assertThatThrownBy(() -> service.summary(TODAY, TODAY.plusDays(1)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.fieldErrors()).containsEntry("to", "The end date can't be in the future."));
        assertThatThrownBy(() -> service.summary(TODAY.minusDays(366), TODAY))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.fieldErrors()).containsEntry("to", "Choose a range of up to 366 days."));
        verify(deliveries, never()).stats(any(), any());
    }

    @Test
    void theLongestRangeIs366Days() {
        givenAnyStats();
        when(deliveries.countByStatus()).thenReturn(Map.of());

        service.summary(TODAY.minusDays(365), TODAY);

        verify(deliveries).stats(TODAY.minusDays(365), TODAY);
    }

    @Test
    void theCsvHasAHeaderOneRowPerDayAndATotal() {
        LocalDate from = TODAY.minusDays(1);
        when(deliveries.stats(from, TODAY)).thenReturn(stats(from, TODAY, new long[][] {{5, 3, 1}, {2, 4, 0}}));
        when(deliveries.stats(from.minusDays(2), from.minusDays(1)))
                .thenReturn(stats(from.minusDays(2), from.minusDays(1), new long[][] {{0, 0, 0}, {0, 0, 0}}));
        when(deliveries.countByStatus()).thenReturn(Map.of());

        String csv = service.summaryCsv(from, TODAY);

        assertThat(csv).isEqualTo("date,booked,delivered,failed\r\n"
                + "2026-11-19,5,3,1\r\n"
                + "2026-11-20,2,4,0\r\n"
                + "total,7,7,1\r\n");
    }

    private void givenAnyStats() {
        when(deliveries.stats(any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(0);
            LocalDate to = call.getArgument(1);
            long days = to.toEpochDay() - from.toEpochDay() + 1;
            return stats(from, to, new long[(int) days][3]);
        });
    }

    /** One {created, delivered, failed} per day from {@code from}. */
    private static DeliveryStats stats(LocalDate from, LocalDate to, long[][] counts) {
        List<DailyCount> perDay = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            perDay.add(new DailyCount(from.plusDays(i), counts[i][0], counts[i][1], counts[i][2]));
        }
        return new DeliveryStats(from, to, perDay);
    }
}
