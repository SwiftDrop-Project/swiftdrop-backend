package com.swiftdrop.courier.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** "Today" for a courier: the current day in Sri Lankan time, whatever time zone the server runs in. */
final class CourierDay {

    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private CourierDay() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** Midnight at the start of today in Sri Lanka, as an instant. */
    static Instant startOfToday(Clock clock) {
        return today(clock).atStartOfDay(ZONE).toInstant();
    }
}
