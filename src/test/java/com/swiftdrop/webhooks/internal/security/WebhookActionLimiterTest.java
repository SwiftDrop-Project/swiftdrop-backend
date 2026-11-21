package com.swiftdrop.webhooks.internal.security;

import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookActionLimiterTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-21T10:00:00Z"));
    private final WebhookActionLimiter limiter = new WebhookActionLimiter(3, clock);
    private final UUID merchant = UUID.randomUUID();

    @Test
    void theActionAfterTheLimitAnswers429WithTheRestOfTheMinute() {
        for (int i = 0; i < 3; i++) {
            limiter.check(merchant);
        }
        clock.advance(Duration.ofSeconds(20));

        assertThatThrownBy(() -> limiter.check(merchant))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(40);
                });
    }

    @Test
    void aNewMinuteStartsAgain() {
        for (int i = 0; i < 3; i++) {
            limiter.check(merchant);
        }
        clock.advance(Duration.ofMinutes(1));

        assertThatCode(() -> limiter.check(merchant)).doesNotThrowAnyException();
    }

    @Test
    void eachMerchantHasTheirOwnCount() {
        for (int i = 0; i < 3; i++) {
            limiter.check(merchant);
        }

        assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        for (int i = 0; i < 3; i++) {
            limiter.check(merchant);
        }
        clock.advance(Duration.ofMillis(59_900));

        assertThatThrownBy(() -> limiter.check(merchant))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(1));
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
