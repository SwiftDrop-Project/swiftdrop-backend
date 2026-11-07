package com.swiftdrop.delivery.internal.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Makes tracking numbers like SD2611004817: "SD", the Sri Lanka year and month of booking (yyMM), then 6 random
 * digits. The digits come from SecureRandom, so seeing your own number does not help to guess someone else's: the
 * public tracking page must not be a way to list other people's parcels. Numbers can repeat (a million per month),
 * so the caller checks that a new one is free and tries again; the unique constraint is the last guard.
 */
@Component
public class TrackingNumberGenerator {

    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyMM");

    private final RandomGenerator random;

    public TrackingNumberGenerator() {
        this(new SecureRandom());
    }

    /** For tests: a random source with known values. */
    TrackingNumberGenerator(RandomGenerator random) {
        this.random = random;
    }

    /** A new tracking number for a parcel booked at {@code now}. */
    public String next(Instant now) {
        String yearMonth = YEAR_MONTH.format(now.atZone(SRI_LANKA));
        return "SD" + yearMonth + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    /** The form tracking numbers are stored in: no spaces, upper case (" sd 2611 004817 " -> "SD2611004817"). */
    public static String normalise(String trackingNumber) {
        return trackingNumber.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }
}
