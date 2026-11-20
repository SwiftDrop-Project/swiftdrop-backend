package com.swiftdrop.assistant.internal.provider;

import tools.jackson.databind.JsonNode;

/** Sends one {@code generateContent} request to Gemini. Replaced by a stub in tests. */
public interface GeminiTransport {

    /**
     * @param request the generateContent body
     * @return the response body
     * @throws ProviderUnavailableException on a network error, timeout or non-2xx answer
     */
    JsonNode generate(JsonNode request);
}
