package com.swiftdrop.webhooks.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When a failed delivery is tried again (API.md "Delivery and retries"): right away, then after 1 min, 5 min,
 * 15 min, 1 h and 6 h, so 6 attempts in all. 408, 429, 5xx and "no answer" (timeout, connection error, URL not allowed
 * right now) are worth retrying; any other answer (other 4xx, 3xx) will not get better and fails at once.
 */
public final class RetryPolicy {

    public static final int MAX_ATTEMPTS = 6;
    /** Wait after failed attempt 1, 2, ... 5. */
    static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));

    private RetryPolicy() {
    }

    /** A 2xx answer. */
    public static boolean isSuccess(Integer statusCode) {
        return statusCode != null && statusCode >= 200 && statusCode < 300;
    }

    /** {@code statusCode} null means there was no HTTP answer (timeout, connection error, URL not allowed). */
    public static boolean isRetryable(Integer statusCode) {
        return statusCode == null || statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    /**
     * When to try again after {@code attemptsMade} failed attempts (1 for the first one); empty when that was the
     * last one.
     */
    public static Optional<Instant> nextAttemptAt(int attemptsMade, Instant now) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade must be at least 1");
        }
        if (attemptsMade >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(now.plus(DELAYS.get(attemptsMade - 1)));
    }
}
