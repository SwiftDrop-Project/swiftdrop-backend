package com.swiftdrop.delivery;

import java.time.LocalDate;
import java.util.List;

/**
 * Parcel counts per day, for the reports (deliveries per day, success and failure rate). Days are Sri Lanka days
 * (Asia/Colombo) from {@code from} to {@code to}, both included. {@code perDay} has every day of that range, oldest
 * first, also days with nothing (all counts 0).
 *
 * <p>{@code created} counts the parcels booked that day; {@code delivered} and {@code failed} count the parcels that
 * reached that status that day. Rates, such as delivered / (delivered + failed), are left to the reporting module.
 */
public record DeliveryStats(LocalDate from, LocalDate to, List<DailyCount> perDay) {

    /** One day's counts. */
    public record DailyCount(LocalDate date, long created, long delivered, long failed) {
    }

    public DeliveryStats {
        // A copy, so that nobody can change the stats later through the list they passed in.
        perDay = List.copyOf(perDay);
    }

    public long totalCreated() {
        return perDay.stream().mapToLong(DailyCount::created).sum();
    }

    public long totalDelivered() {
        return perDay.stream().mapToLong(DailyCount::delivered).sum();
    }

    public long totalFailed() {
        return perDay.stream().mapToLong(DailyCount::failed).sum();
    }
}
