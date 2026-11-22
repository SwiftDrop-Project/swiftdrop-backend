package com.swiftdrop.courier.internal.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** "Today" is the Sri Lankan day (UTC+05:30), whatever the server's time zone. */
class CourierDayTest {

    @Test
    void lateEveningUtcIsAlreadyTomorrowInSriLanka() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T20:00:00Z"), ZoneOffset.UTC); // 01:30 on 6 Nov in Colombo

        assertThat(CourierDay.today(clock)).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(CourierDay.startOfToday(clock)).isEqualTo(Instant.parse("2026-11-05T18:30:00Z"));
    }

    @Test
    void justBeforeSriLankanMidnightIsStillToday() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T18:29:59Z"), ZoneOffset.UTC); // 23:59:59 on 5 Nov

        assertThat(CourierDay.today(clock)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(CourierDay.startOfToday(clock)).isEqualTo(Instant.parse("2026-11-04T18:30:00Z"));
    }
}
