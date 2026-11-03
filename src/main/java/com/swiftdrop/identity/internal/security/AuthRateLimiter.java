package com.swiftdrop.identity.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.identity.internal.security.RateLimitProperties.Limit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Fixed-window request counters per (action, client IP), in memory. Each backend instance counts on its own, which
 * is enough to stop brute force and code flooding from one address.
 */
@Component
public class AuthRateLimiter {

    /** The rate-limited auth actions. */
    public enum Action {
        LOGIN, REGISTER, VERIFY, RESEND, FORGOT, RESET, CHANGE
    }

    private final Map<Action, Limit> limits;
    private final Cache<String, Window> windows;

    public AuthRateLimiter(RateLimitProperties properties) {
        this.limits = Map.of(
                Action.LOGIN, properties.login(),
                Action.REGISTER, properties.register(),
                Action.VERIFY, properties.verify(),
                Action.RESEND, properties.resend(),
                Action.FORGOT, properties.forgot(),
                Action.RESET, properties.reset(),
                Action.CHANGE, properties.change());
        Duration longest = limits.values().stream().map(Limit::window).max(Duration::compareTo).orElseThrow();
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(longest)
                .maximumSize(200_000)
                .build();
    }

    private record Window(long startMillis, int count) {
    }

    /**
     * Counts one request. Empty when it is allowed; otherwise the number of seconds until the window resets
     * (for the Retry-After header).
     */
    public OptionalLong tryAcquire(Action action, String clientIp) {
        Limit limit = limits.get(action);
        long windowMillis = limit.window().toMillis();
        long now = System.currentTimeMillis();
        Window window = windows.asMap().compute(action + "|" + clientIp, (key, current) ->
                current == null || now - current.startMillis() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(current.startMillis(), current.count() + 1));
        if (window.count() <= limit.requests()) {
            return OptionalLong.empty();
        }
        long remainingMillis = window.startMillis() + windowMillis - now;
        return OptionalLong.of(Math.max(1, (remainingMillis + 999) / 1000));
    }

    /** Forgets every counter (tests). */
    public void reset() {
        windows.invalidateAll();
    }
}
