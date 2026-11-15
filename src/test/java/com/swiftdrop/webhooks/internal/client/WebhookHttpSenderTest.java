package com.swiftdrop.webhooks.internal.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.swiftdrop.webhooks.internal.security.WebhookSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP part alone, against a real server on 127.0.0.1 (the JDK's built-in HttpServer). */
class WebhookHttpSenderTest {

    private static final String SECRET = "whsec_test";
    /** Non-ASCII on purpose: the bytes sent must be the UTF-8 bytes that were signed. */
    private static final String BODY = "{\"id\":\"e1\",\"note\":\"Kandy – Peradeniya\"}";

    private final WebhookSigner signer = new WebhookSigner();
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String baseUrl;

    record Received(String path, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/ok", exchange -> answer(exchange, 200));
        server.createContext("/accepted", exchange -> answer(exchange, 202));
        server.createContext("/broken", exchange -> answer(exchange, 503));
        server.createContext("/gone", exchange -> answer(exchange, 410));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/ok");
            answer(exchange, 302);
        });
        server.createContext("/slow", exchange -> {
            sleep(2_000);
            answer(exchange, 200);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTheSignedBodyWithAllHeaders() throws Exception {
        UUID eventId = UUID.randomUUID();
        long timestamp = 1_700_000_000L;
        String signature = signer.sign(SECRET, timestamp, BODY);

        DeliveryOutcome outcome = new WebhookHttpSender().send(
                new OutgoingWebhook(baseUrl + "/ok", eventId, "parcel.delivered", timestamp, signature, BODY));

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome).isEqualTo(new DeliveryOutcome(200, null));
        Received request = received.poll(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(request.header("User-Agent")).isEqualTo(WebhookHttpSender.USER_AGENT);
        assertThat(request.header("X-SwiftDrop-Event-Id")).isEqualTo(eventId.toString());
        assertThat(request.header("X-SwiftDrop-Event-Type")).isEqualTo("parcel.delivered");
        assertThat(request.header("X-SwiftDrop-Timestamp")).isEqualTo("1700000000");
        assertThat(request.header("X-SwiftDrop-Signature")).isEqualTo(signature);
        assertThat(request.body()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));

        // what a merchant would do: verify with the raw body bytes and the timestamp header
        String rawBody = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(signer.matches(SECRET, Long.parseLong(request.header("X-SwiftDrop-Timestamp")), rawBody,
                request.header("X-SwiftDrop-Signature"))).isTrue();
    }

    @Test
    void anyTwoHundredCounts() {
        assertThat(send("/accepted")).isEqualTo(new DeliveryOutcome(202, null));
    }

    @Test
    void aServerErrorIsReportedWithItsCode() {
        DeliveryOutcome outcome = send("/broken");

        assertThat(outcome).isEqualTo(new DeliveryOutcome(503, "HTTP_503"));
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void aGoneAnswerIsNotRetryable() {
        DeliveryOutcome outcome = send("/gone");

        assertThat(outcome).isEqualTo(new DeliveryOutcome(410, "HTTP_410"));
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        DeliveryOutcome outcome = send("/moved");

        assertThat(outcome).isEqualTo(new DeliveryOutcome(302, "HTTP_302"));
        assertThat(outcome.retryable()).isFalse();
        assertThat(received.poll(5, TimeUnit.SECONDS).path()).isEqualTo("/moved");
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("the redirect target was not called").isNull();
    }

    @Test
    void aSlowServerIsATimeout() {
        WebhookHttpSender impatient = new WebhookHttpSender(Duration.ofMillis(300));

        DeliveryOutcome outcome = impatient.send(webhook(baseUrl + "/slow"));

        assertThat(outcome).isEqualTo(DeliveryOutcome.timeout());
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void nobodyListeningIsAConnectionFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        DeliveryOutcome outcome = new WebhookHttpSender().send(webhook("http://127.0.0.1:" + closedPort + "/hook"));

        assertThat(outcome).isEqualTo(DeliveryOutcome.connectionFailed());
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void theOutgoingWebhookNeverPrintsItsUrlOrBody() {
        OutgoingWebhook webhook = new OutgoingWebhook("https://shop.example.com/hook?token=abc", UUID.randomUUID(),
                "parcel.delivered", 1L, "sha256=x", BODY);

        assertThat(webhook.toString()).doesNotContain("token").doesNotContain("Peradeniya").doesNotContain("sha256");
    }

    // ---------- helpers ----------

    private DeliveryOutcome send(String path) {
        return new WebhookHttpSender().send(webhook(baseUrl + path));
    }

    private OutgoingWebhook webhook(String url) {
        return new OutgoingWebhook(url, UUID.randomUUID(), "parcel.delivered", 1_700_000_000L,
                signer.sign(SECRET, 1_700_000_000L, BODY), BODY);
    }

    private void answer(HttpExchange exchange, int status) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Received(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()), body));
        byte[] reply = "merchant reply that must never be stored".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, reply.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
