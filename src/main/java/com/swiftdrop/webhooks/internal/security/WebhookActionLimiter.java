package com.swiftdrop.webhooks.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.shared.error.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test pings and retries per merchant per minute, all their subscriptions together
 * ({@code swiftdrop.webhooks.limits.actions-per-minute}, default 30). Every one of them makes SwiftDrop send a real
 * request to a URL the merchant chose, so a script can't use it to flood a server or crowd out parcel events.
 *
 * <p>A fixed window per merchant that starts with their first action. In memory, per backend instance: a safety net
 * against scripts. The exact per-subscription and per-delivery cooldowns live in the database
 * ({@code WebhookDeliveryService}). Admin retries are not counted.
 */
@Component
public class WebhookActionLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private record Window(Instant start, AtomicInteger count) {
    }

    private final int limit;
    private final Clock clock;
    // a window is dropped a minute after it started (expireAfterWrite: counting doesn't replace the entry)
    private final Cache<UUID, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(100_000)
            .build();

    @Autowired
    public WebhookActionLimiter(WebhookLimitsProperties limits) {
        this(limits.actionsPerMinute(), Clock.systemUTC());
    }

    WebhookActionLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** Counts one test ping or retry; 429 with Retry-After (the rest of the minute) once over the limit. */
    public void check(UUID merchantId) {
        Instant now = clock.instant();
        Window window = windows.asMap().compute(merchantId, (id, current) ->
                current == null || !now.isBefore(current.start().plus(WINDOW))
                        ? new Window(now, new AtomicInteger())
                        : current);
        if (window.count().incrementAndGet() > limit) {
            long waitMillis = Duration.between(now, window.start().plus(WINDOW)).toMillis();
            throw new TooManyRequestsException((waitMillis + 999) / 1000);
        }
    }

    /** For tests: forget all counts. */
    public void reset() {
        windows.invalidateAll();
    }
}
