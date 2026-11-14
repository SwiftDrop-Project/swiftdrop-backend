package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserRoleCounts;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.DayCount;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.Period;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.RecentUser;
import com.swiftdrop.reporting.internal.dto.AdminOverviewResponse.Users;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The admin overview: account counts from identity and parcel counts from delivery. Reporting owns no tables; it
 * only reads through the other modules' Apis.
 */
@Service
public class AdminOverviewService {

    /** Report days are Sri Lanka days, the same as {@link DeliveryApi#stats}. */
    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    /** The overview shows today and the 6 days before it. */
    static final int PERIOD_DAYS = 7;

    static final int RECENT_USERS = 5;

    private final IdentityApi identity;
    private final DeliveryApi deliveries;
    private final Clock clock;

    @Autowired
    public AdminOverviewService(IdentityApi identity, DeliveryApi deliveries) {
        this(identity, deliveries, Clock.system(SRI_LANKA));
    }

    AdminOverviewService(IdentityApi identity, DeliveryApi deliveries, Clock clock) {
        this.identity = identity;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public AdminOverviewResponse overview() {
        LocalDate today = LocalDate.now(clock.withZone(SRI_LANKA));
        DeliveryStats stats = deliveries.stats(today.minusDays(PERIOD_DAYS - 1L), today);

        List<DayCount> perDay = stats.perDay().stream()
                .map(day -> new DayCount(day.date(), day.created(), day.delivered(), day.failed()))
                .toList();
        DayCount todayCount = perDay.isEmpty() ? new DayCount(today, 0, 0, 0) : perDay.getLast();
        Period period = new Period(stats.from(), stats.to(), stats.totalCreated(), stats.totalDelivered(),
                stats.totalFailed(), successRate(stats.totalDelivered(), stats.totalFailed()), perDay);

        List<RecentUser> recentUsers = identity.findRecentUsers(RECENT_USERS).stream()
                .map(user -> new RecentUser(user.id(), user.fullName(), user.role(), user.status(), user.createdAt()))
                .toList();

        return new AdminOverviewResponse(toUsers(identity.getUserRoleCounts()), todayCount, period, recentUsers);
    }

    /** delivered / (delivered + failed) as a percentage with one decimal, or 0.0 when nothing has finished. */
    static BigDecimal successRate(long delivered, long failed) {
        long finished = delivered + failed;
        if (finished == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(delivered * 100L)
                .divide(BigDecimal.valueOf(finished), 1, RoundingMode.HALF_UP);
    }

    private static Users toUsers(UserRoleCounts counts) {
        return new Users(counts.totalUsers(), counts.totalCustomers(), counts.totalMerchants(), counts.totalCouriers(),
                counts.totalDispatchers(), counts.totalAdmins(), counts.pendingMerchants());
    }
}
