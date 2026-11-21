package com.swiftdrop.reporting;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /admin/reports/summary and /summary/export (the Reports &amp; Analytics page), in the Testcontainers database.
 * Other tests add parcels to the same database, so counts are checked against each other, not as fixed numbers.
 */
class AdminReportSummaryTest extends IntegrationTest {

    private static final String SUMMARY = "/api/v1/admin/reports/summary";
    private static final String EXPORT = "/api/v1/admin/reports/summary/export";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Colombo"));

    @Test
    void needsALogin() throws Exception {
        mvc.perform(get(SUMMARY).param("from", TODAY.minusDays(6).toString()).param("to", TODAY.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(EXPORT).param("from", TODAY.toString()).param("to", TODAY.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsMaySeeIt() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(SUMMARY).param("from", TODAY.toString()).param("to", TODAY.toString()).cookie(customer))
                .andExpect(status().isForbidden());
        mvc.perform(get(EXPORT).param("from", TODAY.toString()).param("to", TODAY.toString()).cookie(customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void givesTheRangeThePeriodBeforeItEveryDayAndEveryStatus() throws Exception {
        LocalDate from = TODAY.minusDays(6);

        JsonNode report = json.readTree(mvc.perform(get(SUMMARY).param("from", from.toString())
                        .param("to", TODAY.toString()).cookie(loginAsAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(report.get("totals").get("from").asString()).isEqualTo(from.toString());
        assertThat(report.get("totals").get("to").asString()).isEqualTo(TODAY.toString());
        assertThat(report.get("previous").get("from").asString()).isEqualTo(from.minusDays(7).toString());
        assertThat(report.get("previous").get("to").asString()).isEqualTo(from.minusDays(1).toString());
        assertThat(report.get("perDay")).hasSize(7);
        assertThat(report.get("perDay").get(0).get("date").asString()).isEqualTo(from.toString());

        long created = 0;
        for (JsonNode day : report.get("perDay")) {
            created += day.get("created").asLong();
        }
        assertThat(report.get("totals").get("created").asLong()).isEqualTo(created);
        assertThat(report.get("totals").get("successRate").isNumber()).isTrue();
        assertThat(report.get("byStatus").size()).isEqualTo(7);
        assertThat(report.get("byStatus").has("AT_BRANCH")).isTrue();
    }

    @Test
    void aBadRangeIsAFieldError() throws Exception {
        Cookie admin = loginAsAdmin();

        mvc.perform(get(SUMMARY).param("from", TODAY.toString()).param("to", TODAY.minusDays(1).toString()).cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.from").value("The start date must be on or before the end date."));
        mvc.perform(get(SUMMARY).param("from", TODAY.toString()).param("to", TODAY.plusDays(1).toString()).cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").value("The end date can't be in the future."));
        mvc.perform(get(SUMMARY).param("from", TODAY.minusDays(366).toString()).param("to", TODAY.toString())
                        .cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").value("Choose a range of up to 366 days."));
        mvc.perform(get(SUMMARY).param("to", TODAY.toString()).cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(SUMMARY).param("from", "yesterday").param("to", TODAY.toString()).cookie(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportsTheDaysAsCsvWithATotal() throws Exception {
        LocalDate from = TODAY.minusDays(2);

        String csv = mvc.perform(get(EXPORT).param("from", from.toString()).param("to", TODAY.toString())
                        .cookie(loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"swiftdrop-report-" + from + "-to-" + TODAY + ".csv\""))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("date,booked,delivered,failed");
        assertThat(lines).hasSize(5);
        assertThat(lines[1]).startsWith(from + ",");
        assertThat(lines[4]).startsWith("total,");
    }
}
