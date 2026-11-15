package com.swiftdrop.tracking.internal.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tracking numbers are guessable ({@code SD} + month + 6 digits), so the public lookup allows a limited number of
 * requests per client IP per minute (default 30, {@code swiftdrop.tracking.lookups-per-minute}). In memory, per
 * backend instance.
 */
@Component
public class TrackingRateLimiter {

    private final int limit;
    private final Cache<String, AtomicInteger> counts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    public TrackingRateLimiter(@Value("${swiftdrop.tracking.lookups-per-minute:30}") int limit) {
        this.limit = limit;
    }

    /** Counts one lookup; 429 TOO_MANY_REQUESTS once the client is over the limit for this minute. */
    public void check(String clientIp) {
        AtomicInteger count = counts.get(clientIp == null ? "unknown" : clientIp, key -> new AtomicInteger());
        if (count.incrementAndGet() > limit) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                    "Too many tracking lookups. Please wait a minute and try again.");
        }
    }

    /** For tests: forget all counts. */
    public void reset() {
        counts.invalidateAll();
    }
}
