package com.swiftdrop.delivery.internal.persistence;

/**
 * One row of a per-day count query: a Sri Lanka day as text (yyyy-MM-dd, so no driver date conversion is involved)
 * and how many parcels it counted. Spring Data fills it from the query's column aliases {@code day} and
 * {@code total}.
 */
public interface DailyTotal {

    String getDay();

    long getTotal();
}
