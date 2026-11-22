package com.swiftdrop.webhooks.internal.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API.md "Delivery and retries": right away, then 1 min, 5 min, 15 min, 1 h, 6 h; 6 attempts in all. */
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    @Test
    void waitsGrowAfterEachFailedAttempt() {
        assertThat(RetryPolicy.nextAttemptAt(1, NOW)).contains(NOW.plus(Duration.ofMinutes(1)));
        assertThat(RetryPolicy.nextAttemptAt(2, NOW)).contains(NOW.plus(Duration.ofMinutes(5)));
        assertThat(RetryPolicy.nextAttemptAt(3, NOW)).contains(NOW.plus(Duration.ofMinutes(15)));
        assertThat(RetryPolicy.nextAttemptAt(4, NOW)).contains(NOW.plus(Duration.ofHours(1)));
        assertThat(RetryPolicy.nextAttemptAt(5, NOW)).contains(NOW.plus(Duration.ofHours(6)));
    }

    @Test
    void theSixthFailedAttemptIsTheLast() {
        assertThat(RetryPolicy.MAX_ATTEMPTS).isEqualTo(6);
        assertThat(RetryPolicy.nextAttemptAt(6, NOW)).isEmpty();
        assertThat(RetryPolicy.nextAttemptAt(7, NOW)).isEmpty();
    }

    @Test
    void attemptsStartAtOne() {
        assertThatThrownBy(() -> RetryPolicy.nextAttemptAt(0, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504, 599})
    void serverTroubleIsRetried(int status) {
        assertThat(RetryPolicy.isRetryable(status)).isTrue();
    }

    @Test
    void noAnswerIsRetried() {
        assertThat(RetryPolicy.isRetryable(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 307, 400, 401, 403, 404, 405, 410, 413, 422})
    void otherAnswersAreNotRetried(int status) {
        assertThat(RetryPolicy.isRetryable(status)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204, 299})
    void anyTwoHundredIsASuccess(int status) {
        assertThat(RetryPolicy.isSuccess(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 199, 300, 404, 500})
    void nothingElseIsASuccess(int status) {
        assertThat(RetryPolicy.isSuccess(status)).isFalse();
        assertThat(RetryPolicy.isSuccess(null)).isFalse();
    }
}
