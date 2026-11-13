package com.swiftdrop.dispatch.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** "Today" for dispatch is the Sri Lanka day, whatever time zone the server runs in. */
final class DispatchDay {

    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private DispatchDay() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    static Instant startOfToday(Clock clock) {
        return today(clock).atStartOfDay(ZONE).toInstant();
    }
}
