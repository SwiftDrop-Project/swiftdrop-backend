package com.swiftdrop.webhooks.internal.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * POSTs one webhook and reports what happened. Never throws for a merchant's broken server: every problem becomes a
 * {@link DeliveryOutcome}. Redirects are not followed (a redirect could lead to an address the URL check never saw).
 * The response body is never read: only the status counts, and nothing the merchant sends back is stored or logged.
 *
 * <p>Only the HTTP part: the caller checks the URL ({@code CallbackUrlValidator.isAllowedNow}) and signs first.
 */
@Component
public class WebhookHttpSender {

    static final String USER_AGENT = "SwiftDrop-Webhooks/1.0";
    /** API.md: a 2xx answer within 10 seconds counts as delivered. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Duration requestTimeout;

    @Autowired
    public WebhookHttpSender() {
        this(REQUEST_TIMEOUT);
    }

    /** Tests use a short timeout. */
    WebhookHttpSender(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(requestTimeout.compareTo(CONNECT_TIMEOUT) < 0 ? requestTimeout : CONNECT_TIMEOUT)
                .build();
    }

    public DeliveryOutcome send(OutgoingWebhook webhook) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(webhook.url()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-SwiftDrop-Event-Id", webhook.eventId().toString())
                    .header("X-SwiftDrop-Event-Type", webhook.eventType())
                    .header("X-SwiftDrop-Timestamp", Long.toString(webhook.timestamp()))
                    .header("X-SwiftDrop-Signature", webhook.signature())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(webhook.body().getBytes(StandardCharsets.UTF_8)))
                    .build();
        } catch (IllegalArgumentException ex) {
            return DeliveryOutcome.urlNotAllowed(); // not a URL the client accepts (checked before, so unexpected)
        }

        try {
            // ofInputStream returns as soon as the status line and headers arrive; closing the stream drops the body,
            // so a server that trickles its body forever can't hold this thread
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                return DeliveryOutcome.answered(response.statusCode());
            }
        } catch (HttpTimeoutException ex) {
            return DeliveryOutcome.timeout(); // also a connect timeout (HttpConnectTimeoutException)
        } catch (IOException ex) {
            return DeliveryOutcome.connectionFailed();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DeliveryOutcome.connectionFailed();
        }
    }
}
