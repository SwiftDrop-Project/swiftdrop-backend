package com.swiftdrop.assistant.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code swiftdrop.assistant.*}: which model answers, and the limits.
 *
 * @param provider          {@code gemini} (Gemini writes the answers from the data) or {@code rules} (plain answers);
 *                          gemini needs {@code gemini.api-key}
 * @param messagesPerMinute chat messages and MCP tool calls per user (or IP) per minute
 */
@ConfigurationProperties("swiftdrop.assistant")
public record AssistantProperties(
        @DefaultValue("rules") String provider,
        @DefaultValue("20") int messagesPerMinute,
        @DefaultValue Gemini gemini) {

    /**
     * @param apiKey  Google AI Studio key; never logged or sent to the browser
     * @param model   e.g. gemini-3.5-flash
     * @param baseUrl the Generative Language API, up to and including the version
     */
    public record Gemini(
            @DefaultValue("") String apiKey,
            @DefaultValue("gemini-3.5-flash") String model,
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("30s") Duration timeout) {
    }

    public boolean geminiEnabled() {
        return "gemini".equalsIgnoreCase(provider) && gemini.apiKey() != null && !gemini.apiKey().isBlank();
    }
}
