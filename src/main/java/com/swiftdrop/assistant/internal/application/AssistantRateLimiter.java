package com.swiftdrop.assistant.internal.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Chat messages and MCP tool calls per client per minute (default 20, {@code swiftdrop.assistant.messages-per-minute}),
 * so nobody can run up the model's free quota or use the assistant to guess tracking numbers. In memory, per instance.
 */
@Component
public class AssistantRateLimiter {

    private final int limit;
    private final Cache<String, AtomicInteger> counts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    public AssistantRateLimiter(AssistantProperties properties) {
        this.limit = properties.messagesPerMinute();
    }

    /** Counts one message; 429 TOO_MANY_REQUESTS once the client is over the limit for this minute. */
    public void check(String clientKey) {
        AtomicInteger count = counts.get(clientKey == null ? "unknown" : clientKey, key -> new AtomicInteger());
        if (count.incrementAndGet() > limit) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                    "Too many messages. Please wait a minute and try again.");
        }
    }

    /** For tests: forget all counts. */
    public void reset() {
        counts.invalidateAll();
    }
}
