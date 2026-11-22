package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini's REST API ({@code POST {baseUrl}/models/{model}:generateContent}). The key goes in the
 * {@code x-goog-api-key} header, never in the URL, so it can't end up in logs.
 */
@Component
class HttpGeminiTransport implements GeminiTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpGeminiTransport.class);

    private final AssistantProperties.Gemini gemini;
    private final JsonMapper json;
    private final HttpClient client;

    HttpGeminiTransport(AssistantProperties properties, JsonMapper json) {
        this.gemini = properties.gemini();
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(gemini.timeout()).build();
    }

    @Override
    public JsonNode generate(JsonNode request) {
        String model = URLEncoder.encode(gemini.model(), StandardCharsets.UTF_8);
        HttpRequest http = HttpRequest.newBuilder(URI.create(gemini.baseUrl() + "/models/" + model
                        + ":generateContent"))
                .timeout(gemini.timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", gemini.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(http, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderUnavailableException("Gemini unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while waiting for Gemini", e);
        }
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            log.warn("Gemini answered {}: {}", response.statusCode(), body.substring(0, Math.min(300, body.length()))
                    .replaceAll("\\s+", " "));
            throw new ProviderUnavailableException("Gemini answered HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }
}
