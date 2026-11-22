package com.swiftdrop.delivery;

import com.swiftdrop.delivery.DeliveryStats.DailyCount;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatsTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);
    private static final LocalDate NOV_7 = LocalDate.of(2026, 11, 7);

    @Test
    void totalsAddUpEveryDay() {
        var stats = new DeliveryStats(NOV_5, NOV_7, List.of(
                new DailyCount(NOV_5, 12, 8, 1),
                new DailyCount(NOV_6, 0, 0, 0),
                new DailyCount(NOV_7, 15, 17, 2)));

        assertThat(stats.totalCreated()).isEqualTo(27);
        assertThat(stats.totalDelivered()).isEqualTo(25);
        assertThat(stats.totalFailed()).isEqualTo(3);
    }

    @Test
    void totalsAreZeroWhenNothingHappened() {
        var stats = new DeliveryStats(NOV_5, NOV_6, List.of(
                new DailyCount(NOV_5, 0, 0, 0),
                new DailyCount(NOV_6, 0, 0, 0)));

        assertThat(stats.totalCreated()).isZero();
        assertThat(stats.totalDelivered()).isZero();
        assertThat(stats.totalFailed()).isZero();
    }

    @Test
    void changingTheCallersListLaterDoesNotChangeTheStats() {
        var days = new ArrayList<DailyCount>();
        days.add(new DailyCount(NOV_5, 12, 8, 1));
        var stats = new DeliveryStats(NOV_5, NOV_5, days);

        days.add(new DailyCount(NOV_6, 100, 100, 100));

        assertThat(stats.perDay()).hasSize(1);
        assertThat(stats.totalCreated()).isEqualTo(12);
    }
}
