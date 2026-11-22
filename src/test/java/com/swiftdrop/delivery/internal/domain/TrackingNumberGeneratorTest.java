package com.swiftdrop.delivery.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingNumberGeneratorTest {

    private static final Instant NOVEMBER = Instant.parse("2026-11-06T04:00:00Z");

    @Test
    void aNumberIsSdThenYearAndMonthThenSixDigits() {
        String number = new TrackingNumberGenerator().next(NOVEMBER);

        assertThat(number).matches("SD2611\\d{6}");
    }

    @Test
    void smallNumbersArePaddedWithZeros() {
        var generator = new TrackingNumberGenerator(always(4817));

        assertThat(generator.next(NOVEMBER)).isEqualTo("SD2611004817");
    }

    @Test
    void theMonthIsTheMonthInSriLanka() {
        // 19:00 UTC on 30 November is already 00:30 on 1 December in Sri Lanka (UTC+5:30)
        Instant lateOnTheLastDay = Instant.parse("2026-11-30T19:00:00Z");

        assertThat(new TrackingNumberGenerator(always(1)).next(lateOnTheLastDay)).isEqualTo("SD2612000001");
    }

    @Test
    void numbersAreRandom() {
        var generator = new TrackingNumberGenerator();
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            numbers.add(generator.next(NOVEMBER));
        }

        // a million numbers per month: 100 random ones are almost never the same
        assertThat(numbers).hasSizeGreaterThan(95);
    }

    @Test
    void normaliseRemovesSpacesAndUpperCases() {
        assertThat(TrackingNumberGenerator.normalise(" sd 2611 004817 ")).isEqualTo("SD2611004817");
    }

    /** A random source that always gives the same number, so a test knows the result. */
    private static RandomGenerator always(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }
}
