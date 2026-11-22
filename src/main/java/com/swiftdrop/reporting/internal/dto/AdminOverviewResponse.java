package com.swiftdrop.reporting.internal.dto;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body of GET /admin/reports/overview: the figures on the admin's home page (API.md "Reports (admin)").
 * Days are Sri Lanka days (Asia/Colombo).
 */
public record AdminOverviewResponse(Users users, DayCount today, Period last7Days, List<RecentUser> recentUsers) {

    public AdminOverviewResponse {
        recentUsers = List.copyOf(recentUsers);
    }

    /** Accounts per role. Removed accounts are not counted. */
    public record Users(long total, long customers, long merchants, long couriers, long dispatchers, long admins,
                        long pendingMerchants) {
    }

    /** One day's parcel counts: booked that day, and reached DELIVERED or FAILED that day. */
    public record DayCount(LocalDate date, long created, long delivered, long failed) {
    }

    /**
     * Totals for a range of days, both included, plus every day of it (oldest first).
     *
     * @param successRate delivered / (delivered + failed) as a percentage with one decimal; 0.0 when both are 0
     */
    public record Period(LocalDate from, LocalDate to, long created, long delivered, long failed,
                         BigDecimal successRate, List<DayCount> perDay) {

        public Period {
            perDay = List.copyOf(perDay);
        }
    }

    /** A recently created account. No email or phone: the overview does not need contact details. */
    public record RecentUser(UUID id, String fullName, Role role, UserStatus status, Instant createdAt) {

        /** Keeps the name out of logs. */
        @Override
        public String toString() {
            return "RecentUser[id=" + id + ", role=" + role + ", status=" + status + "]";
        }
    }
}
