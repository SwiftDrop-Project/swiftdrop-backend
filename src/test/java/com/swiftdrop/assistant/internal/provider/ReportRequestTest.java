package com.swiftdrop.assistant.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.swiftdrop.assistant.internal.tools.ServiceReportTool.Period;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** How an admin's report request is read. Today is Thursday 19 Nov 2026. */
class ReportRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static ReportRequest read(String text) {
        return ReportRequest.read(text, TODAY);
    }

    @Test
    void recognisesReportRequests() {
        assertThat(ReportRequest.isReport("Give me today's report")).isTrue();
        assertThat(ReportRequest.isReport("weekly summary please")).isTrue();
        assertThat(ReportRequest.isReport("How did we do this month?")).isTrue();
        assertThat(ReportRequest.isReport("How much to send 2 kg to Kandy?")).isFalse();
        assertThat(ReportRequest.isReport("Where is SD2611874236?")).isFalse();
    }

    @Test
    void readsThePeriodAndTheDay() {
        assertThat(read("today's report")).isEqualTo(new ReportRequest(Period.DAILY, TODAY));
        assertThat(read("report for yesterday")).isEqualTo(new ReportRequest(Period.DAILY, TODAY.minusDays(1)));
        assertThat(read("this week's report")).isEqualTo(new ReportRequest(Period.WEEKLY, TODAY));
        assertThat(read("report for last week")).isEqualTo(new ReportRequest(Period.WEEKLY, TODAY.minusWeeks(1)));
        assertThat(read("monthly report")).isEqualTo(new ReportRequest(Period.MONTHLY, TODAY));
        assertThat(read("summary of last month")).isEqualTo(new ReportRequest(Period.MONTHLY,
                TODAY.minusMonths(1)));
        assertThat(read("report for October")).isEqualTo(new ReportRequest(Period.MONTHLY,
                LocalDate.of(2026, 10, 1)));
        // December hasn't come yet this year, so it's last December.
        assertThat(read("report for December")).isEqualTo(new ReportRequest(Period.MONTHLY,
                LocalDate.of(2025, 12, 1)));
        assertThat(read("report for 2026-11-10")).isEqualTo(new ReportRequest(Period.DAILY,
                LocalDate.of(2026, 11, 10)));
        assertThat(read("weekly report for 10 Nov")).isEqualTo(new ReportRequest(Period.WEEKLY,
                LocalDate.of(2026, 11, 10)));
        assertThat(read("give me a report")).isEqualTo(new ReportRequest(null, TODAY));
    }

    @Test
    void wordsThatStartLikeMonthsAreNotMonths() {
        assertThat(read("May I see a separate report on the market?")).isEqualTo(new ReportRequest(null, TODAY));
        assertThat(ReportRequest.hasPeriod("busiest day")).isFalse();
        assertThat(read("report for May 2026")).isEqualTo(new ReportRequest(Period.MONTHLY,
                LocalDate.of(2026, 5, 1)));
        assertThat(read("monthly report for September")).isEqualTo(new ReportRequest(Period.MONTHLY,
                LocalDate.of(2026, 9, 1)));
    }
}
