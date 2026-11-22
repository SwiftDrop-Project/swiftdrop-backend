package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.DeliveryStats.DailyCount;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserRoleCounts;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOverviewServiceTest {

    /** 20:00 UTC on 12 November is already 01:30 on 13 November in Sri Lanka. */
    private static final Instant NOW = Instant.parse("2026-11-12T20:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 7);

    private final IdentityApi identity = mock(IdentityApi.class);
    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final AdminOverviewService service =
            new AdminOverviewService(identity, deliveries, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void combinesAccountCountsParcelCountsAndNewestAccounts() {
        when(identity.getUserRoleCounts()).thenReturn(new UserRoleCounts(30, 20, 5, 3, 1, 1, 2));
        UserSummary newest = new UserSummary(UUID.randomUUID(), "Ayesha Fernando", "ayesha@test.lk", "+94711234567",
                Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-11-12T19:00:00Z"));
        when(identity.findRecentUsers(AdminOverviewService.RECENT_USERS)).thenReturn(List.of(newest));
        when(deliveries.stats(WEEK_START, TODAY)).thenReturn(week(new long[][] {
                {5, 3, 0}, {2, 1, 1}, {0, 0, 0}, {4, 2, 0}, {6, 1, 0}, {3, 0, 0}, {7, 1, 0}}));

        AdminOverviewResponse overview = service.overview();

        assertThat(overview.users()).isEqualTo(new AdminOverviewResponse.Users(30, 20, 5, 3, 1, 1, 2));
        assertThat(overview.today()).isEqualTo(new AdminOverviewResponse.DayCount(TODAY, 7, 1, 0));
        assertThat(overview.last7Days().from()).isEqualTo(WEEK_START);
        assertThat(overview.last7Days().to()).isEqualTo(TODAY);
        assertThat(overview.last7Days().perDay()).hasSize(7);
        assertThat(overview.last7Days().created()).isEqualTo(27);
        assertThat(overview.last7Days().delivered()).isEqualTo(8);
        assertThat(overview.last7Days().failed()).isEqualTo(1);
        assertThat(overview.last7Days().successRate()).isEqualByComparingTo("88.9");
        assertThat(overview.recentUsers()).singleElement().satisfies(user -> {
            assertThat(user.id()).isEqualTo(newest.id());
            assertThat(user.fullName()).isEqualTo("Ayesha Fernando");
            assertThat(user.role()).isEqualTo(Role.CUSTOMER);
            assertThat(user.createdAt()).isEqualTo(newest.createdAt());
        });
        // Sri Lanka's date decides "today", not UTC's
        verify(deliveries).stats(WEEK_START, TODAY);
    }

    @Test
    void aWeekWithNoParcelsGivesZeros() {
        when(identity.getUserRoleCounts()).thenReturn(new UserRoleCounts(1, 0, 0, 0, 0, 1, 0));
        when(identity.findRecentUsers(AdminOverviewService.RECENT_USERS)).thenReturn(List.of());
        when(deliveries.stats(WEEK_START, TODAY)).thenReturn(week(new long[7][3]));

        AdminOverviewResponse overview = service.overview();

        assertThat(overview.today()).isEqualTo(new AdminOverviewResponse.DayCount(TODAY, 0, 0, 0));
        assertThat(overview.last7Days().successRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(overview.recentUsers()).isEmpty();
    }

    @Test
    void successRateHasOneDecimalAndNeverDividesByZero() {
        assertThat(AdminOverviewService.successRate(0, 0)).isEqualByComparingTo("0.0");
        assertThat(AdminOverviewService.successRate(8, 1)).isEqualByComparingTo("88.9");
        assertThat(AdminOverviewService.successRate(2, 1)).isEqualByComparingTo("66.7");
        assertThat(AdminOverviewService.successRate(5, 0)).isEqualByComparingTo("100.0");
        assertThat(AdminOverviewService.successRate(0, 4)).isEqualByComparingTo("0.0");
        assertThat(AdminOverviewService.successRate(8, 1).scale()).isEqualTo(1);
    }

    /** Stats for WEEK_START..TODAY; each row is {created, delivered, failed}. */
    private static DeliveryStats week(long[][] rows) {
        List<DailyCount> perDay = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            perDay.add(new DailyCount(WEEK_START.plusDays(i), rows[i][0], rows[i][1], rows[i][2]));
        }
        return new DeliveryStats(WEEK_START, TODAY, perDay);
    }
}
