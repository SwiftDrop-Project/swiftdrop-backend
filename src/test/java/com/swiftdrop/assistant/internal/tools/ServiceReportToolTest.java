package com.swiftdrop.assistant.internal.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.DeliveryStats.DailyCount;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserRoleCounts;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.tracking.ParcelBreakdown;
import com.swiftdrop.tracking.TrackingApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The admin report: periods, partial periods, the period before and the figures, with mocked APIs. */
class ServiceReportToolTest {

    /** Thursday 19 Nov 2026 in Sri Lanka. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final TrackingApi tracking = mock(TrackingApi.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private final ServiceReportTool tool = new ServiceReportTool(deliveries, tracking, identity,
            Clock.fixed(TODAY.atTime(10, 0).toInstant(ZoneOffset.ofHoursMinutes(5, 30)), Words.SRI_LANKA));
    private final JsonMapper json = JsonMapper.builder().build();

    private final Caller admin = new Caller(UUID.randomUUID(), "ADMIN", "a");

    @BeforeEach
    void setUp() {
        // Each day: 3 booked, 2 delivered, 1 failed (the period before: 2 booked, 1 delivered, 1 failed).
        when(deliveries.stats(any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(0);
            LocalDate to = call.getArgument(1);
            boolean before = to.isBefore(LocalDate.of(2026, 11, 16));
            List<DailyCount> days = new ArrayList<>();
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                days.add(before ? new DailyCount(day, 2, 1, 1) : new DailyCount(day, 3, 2, 1));
            }
            return new DeliveryStats(from, to, days);
        });
        Map<District, Long> pickups = new LinkedHashMap<>();
        pickups.put(District.COLOMBO, 9L);
        pickups.put(District.KANDY, 3L);
        when(tracking.breakdown(any(), any())).thenAnswer(call -> new ParcelBreakdown(call.getArgument(0),
                call.getArgument(1), pickups, Map.of(District.GALLE, 4L),
                Map.of(FailureReason.RECIPIENT_UNAVAILABLE, 2L)));
        when(deliveries.countByStatus()).thenReturn(Map.of(ParcelStatus.CREATED, 5L, ParcelStatus.DELIVERED, 20L));
        when(identity.getUserRoleCounts()).thenReturn(new UserRoleCounts(30, 20, 5, 3, 1, 1, 2));
    }

    @Test
    void onlyAdminsHaveTheTool() {
        assertThat(tool.availableTo(admin)).isTrue();
        assertThat(tool.availableTo(new Caller(UUID.randomUUID(), "DISPATCHER", "d"))).isFalse();
        assertThat(tool.availableTo(new Caller(UUID.randomUUID(), "CUSTOMER", "c"))).isFalse();
        assertThat(tool.availableTo(Caller.anonymous("v"))).isFalse();
    }

    @Test
    void aWeekRunsMondayToSundayAndIsCountedUpToToday() {
        Map<String, Object> data = tool.report(ServiceReportTool.Period.WEEKLY, TODAY, TODAY);

        assertThat(data).containsEntry("from", "2026-11-16").containsEntry("to", "2026-11-22")
                .containsEntry("countedTo", "2026-11-19").containsEntry("partial", true)
                .containsEntry("rangeText", "16 Nov 2026 to 22 Nov 2026 (up to 19 Nov 2026)");
        verify(deliveries).stats(LocalDate.of(2026, 11, 16), TODAY);
        // The same four days of the week before.
        verify(deliveries).stats(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 12));

        assertThat(data).containsEntry("booked", 12L).containsEntry("delivered", 8L).containsEntry("failed", 4L);
        assertThat((BigDecimal) data.get("successRate")).isEqualByComparingTo("66.7");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> changes = (Map<String, Map<String, Object>>) data.get("changes");
        assertThat(changes.get("booked")).containsEntry("difference", 4L);
        assertThat((BigDecimal) changes.get("booked").get("percent")).isEqualByComparingTo("50.0");
        assertThat((List<?>) data.get("perDay")).hasSize(4);
        assertThat(data.get("busiestDay")).isEqualTo(Map.of("date", "2026-11-16", "dateText", "Mon 16 Nov",
                "booked", 3L));
        assertThat((List<?>) data.get("topPickupDistricts")).first()
                .isEqualTo(Map.of("district", "Colombo", "count", 9L));
        assertThat((List<?>) data.get("statusNow")).hasSize(ParcelStatus.values().length);
        assertThat(data.get("accountsNow")).isEqualTo(Map.of("total", 30L, "customers", 20L, "merchants", 5L,
                "couriers", 3L, "dispatchers", 1L, "pendingMerchants", 2L));
    }

    @Test
    void aDayIsComparedWithTheDayBeforeAndHasNoPerDayRows() {
        Map<String, Object> data = tool.report(ServiceReportTool.Period.DAILY, TODAY, TODAY);

        assertThat(data).containsEntry("from", "2026-11-19").containsEntry("partial", false)
                .containsEntry("rangeText", "19 Nov 2026").doesNotContainKeys("perDay", "busiestDay");
        verify(deliveries).stats(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 18));
    }

    @Test
    void aMonthIsComparedWithTheSameDaysOfTheMonthBeforeWithinThatMonth() {
        Map<String, Object> november = tool.report(ServiceReportTool.Period.MONTHLY, TODAY, TODAY);
        assertThat(november).containsEntry("from", "2026-11-01").containsEntry("to", "2026-11-30");
        verify(deliveries).stats(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19));

        // 31 March: the month before is all of February, never days of March.
        LocalDate march = LocalDate.of(2026, 3, 31);
        tool.report(ServiceReportTool.Period.MONTHLY, march, march);
        verify(deliveries).stats(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
    }

    @Test
    void runGivesAReportCardOrAClearMessage() {
        ObjectNode lastMonth = json.createObjectNode().put("period", "monthly").put("date", "2026-10-05");
        ToolResult result = tool.run(lastMonth, admin);
        assertThat(result.error()).isFalse();
        assertThat(result.card().type()).isEqualTo("REPORT");
        assertThat(result.data()).containsEntry("partial", false).containsEntry("rangeText",
                "1 Oct 2026 to 31 Oct 2026");

        assertThat(tool.run(json.createObjectNode().put("period", "yearly"), admin).error()).isTrue();
        assertThat(tool.run(json.createObjectNode().put("period", "DAILY").put("date", "19/11/2026"), admin)
                .error()).isTrue();
        assertThat(tool.run(json.createObjectNode().put("period", "DAILY").put("date", "2026-12-01"), admin)
                .data().get("error")).asString().contains("hasn't started");
    }

    @Test
    void noFinishedDeliveriesMeansNoSuccessRate() {
        DeliveryStats none = new DeliveryStats(TODAY, TODAY, List.of(new DailyCount(TODAY, 4, 0, 0)));
        assertThat(ServiceReportTool.successRate(none)).isNull();
        assertThat(ServiceReportTool.change(4, 0)).containsEntry("difference", 4L).containsEntry("percent", null);
    }
}
