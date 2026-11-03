package com.swiftdrop.identity.internal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-IP limits for the auth endpoints ({@code swiftdrop.security.rate-limit.*} in application.yml). Each limit is
 * "at most {@code requests} per {@code window}" for one client IP. Missing values use the defaults below.
 */
@ConfigurationProperties("swiftdrop.security.rate-limit")
public record RateLimitProperties(Limit login, Limit register, Limit verify, Limit resend, Limit forgot,
                                  Limit reset, Limit change) {

    public record Limit(int requests, Duration window) {

        public Limit {
            if (requests < 1) {
                throw new IllegalArgumentException("rate limit requests must be at least 1");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate limit window must be positive");
            }
        }
    }

    public RateLimitProperties {
        login = orDefault(login, 10, Duration.ofMinutes(1));
        register = orDefault(register, 10, Duration.ofMinutes(10));
        verify = orDefault(verify, 10, Duration.ofMinutes(1));
        resend = orDefault(resend, 5, Duration.ofMinutes(1));
        forgot = orDefault(forgot, 5, Duration.ofMinutes(1));
        reset = orDefault(reset, 10, Duration.ofMinutes(1));
        change = orDefault(change, 10, Duration.ofMinutes(1));
    }

    private static Limit orDefault(Limit limit, int requests, Duration window) {
        return limit != null ? limit : new Limit(requests, window);
    }
}
