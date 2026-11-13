package com.swiftdrop.webhooks.internal.application;

import com.sun.net.httpserver.HttpServer;
import com.swiftdrop.FakeHostResolver;
import com.swiftdrop.IntegrationTest;
import com.swiftdrop.webhooks.internal.client.WebhookHttpSender;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryAttempts;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import com.swiftdrop.webhooks.internal.security.CallbackUrlValidator;
import com.swiftdrop.webhooks.internal.security.SecretCipher;
import com.swiftdrop.webhooks.internal.security.WebhookProperties;
import com.swiftdrop.webhooks.internal.security.WebhookSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker end to end: real database, real HTTP to a local server on 127.0.0.1.
 *
 * <p>The dispatcher is built here rather than taken from Spring: its URL check allows http:// and loopback (so the
 * local server can be reached; the app's own check never does) and its clock is set by the test, so waiting
 * "1 minute" for a retry is one line. The clock starts in 2001: deliveries other tests leave PENDING are due "now"
 * and are never picked up here. Every row this test makes is deleted afterwards.
 */
class WebhookDispatcherTest extends IntegrationTest {

    private static final String SECRET = "whsec_dispatcher_test";
    private static final Instant START = Instant.parse("2001-01-01T10:00:00Z");

    @Autowired
    WebhookDeliveryAttempts attempts;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Autowired
    SecretCipher cipher;

    @Autowired
    JdbcTemplate jdbc;

    private final WebhookSigner signer = new WebhookSigner();
    private final MutableClock clock = new MutableClock(START);
    private final UUID merchant = UUID.randomUUID();
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
    private final AtomicInteger answer = new AtomicInteger(200);
    private HttpServer server;
    private String hookUrl;
    private WebhookDispatcher dispatcher;

    record Received(String eventId, String eventType, long timestamp, String signature, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/hook", exchange -> {
            var headers = exchange.getRequestHeaders();
            received.add(new Received(headers.getFirst("X-SwiftDrop-Event-Id"),
                    headers.getFirst("X-SwiftDrop-Event-Type"),
                    Long.parseLong(headers.getFirst("X-SwiftDrop-Timestamp")),
                    headers.getFirst("X-SwiftDrop-Signature"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        server.start();
        hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        CallbackUrlValidator localUrlsAllowed = new CallbackUrlValidator(
                new WebhookProperties("test-only-webhook-key-0123456789-abcdefghijklmnop", true),
                new FakeHostResolver());
        dispatcher = new WebhookDispatcher(attempts, deliveries, subscriptions, localUrlsAllowed, cipher, signer,
                new WebhookHttpSender(), 10, clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        jdbc.update("delete from webhooks.deliveries where merchant_id = ?", merchant);
        jdbc.update("delete from webhooks.subscriptions where merchant_id = ?", merchant);
    }

    @Test
    void aTwoHundredMarksTheDeliverySucceeded() throws Exception {
        WebhookSubscription subscription = subscribe(hookUrl, SECRET);
        WebhookDelivery delivery = due(subscription, WebhookEventType.PARCEL_DELIVERED);

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        Received request = received.poll(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.eventId()).isEqualTo(delivery.getEventId().toString());
        assertThat(request.eventType()).isEqualTo("parcel.delivered");
        assertThat(request.body()).isEqualTo(delivery.getPayload());
        assertThat(request.timestamp()).isEqualTo(START.getEpochSecond());
        assertThat(signer.matches(SECRET, request.timestamp(), request.body(), request.signature())).isTrue();

        assertThat(row(delivery)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 1)
                .containsEntry("last_status_code", 200).containsEntry("last_error", null)
                .containsEntry("next_attempt_at", null);
        assertThat(time(row(delivery).get("delivered_at"))).isEqualTo(START);
        assertThat(dispatcher.dispatchDue()).as("nothing left").isZero();
    }

    @Test
    void aServerErrorIsTriedAgainAfterOneMinute() throws Exception {
        answer.set(500);
        WebhookDelivery delivery = due(subscribe(hookUrl, SECRET), WebhookEventType.PARCEL_DELIVERED);

        dispatcher.dispatchDue();

        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(row(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_status_code", 500).containsEntry("last_error", "HTTP_500");
        assertThat(time(row(delivery).get("next_attempt_at"))).isEqualTo(START.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofSeconds(59));
        assertThat(dispatcher.dispatchDue()).as("not due yet").isZero();

        answer.set(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(row(delivery)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2);
    }

    @Test
    void afterSixFailedAttemptsTheDeliveryFails() {
        answer.set(503);
        WebhookDelivery delivery = due(subscribe(hookUrl, SECRET), WebhookEventType.PARCEL_DELIVERED);
        Duration[] waits = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1),
                Duration.ofHours(6)};

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(dispatcher.dispatchDue()).as("attempt " + attempt).isEqualTo(1);
            assertThat(row(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", attempt);
            assertThat(time(row(delivery).get("next_attempt_at"))).isEqualTo(clock.instant().plus(waits[attempt - 1]));
            clock.advance(waits[attempt - 1]);
        }
        assertThat(dispatcher.dispatchDue()).as("attempt 6").isEqualTo(1);

        assertThat(row(delivery)).containsEntry("status", "FAILED").containsEntry("attempts", 6)
                .containsEntry("last_error", "HTTP_503").containsEntry("next_attempt_at", null);
        assertThat(received).hasSize(6);
        clock.advance(Duration.ofDays(1));
        assertThat(dispatcher.dispatchDue()).as("no automatic attempt after FAILED").isZero();
    }

    @Test
    void aClientErrorFailsAtOnce() {
        answer.set(400);
        WebhookDelivery delivery = due(subscribe(hookUrl, SECRET), WebhookEventType.PARCEL_DELIVERED);

        dispatcher.dispatchDue();

        assertThat(row(delivery)).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_status_code", 400).containsEntry("last_error", "HTTP_400");
    }

    @Test
    void parcelEventsOfATurnedOffSubscriptionAreNotSent() throws Exception {
        WebhookSubscription subscription = subscribe(hookUrl, SECRET);
        WebhookDelivery delivery = due(subscription, WebhookEventType.PARCEL_DELIVERED);
        subscription.deactivate(START);
        subscriptions.saveAndFlush(subscription);

        dispatcher.dispatchDue();

        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(row(delivery)).containsEntry("status", "FAILED").containsEntry("attempts", 0)
                .containsEntry("last_status_code", null)
                .containsEntry("last_error", WebhookDispatcher.SUBSCRIPTION_INACTIVE);
    }

    @Test
    void aTestPingIsSentEvenWhileTurnedOff() throws Exception {
        WebhookSubscription subscription = subscribe(hookUrl, SECRET);
        subscription.deactivate(START);
        subscriptions.saveAndFlush(subscription);
        WebhookDelivery ping = due(subscription, WebhookEventType.WEBHOOK_PING);

        dispatcher.dispatchDue();

        assertThat(received.poll(5, TimeUnit.SECONDS).eventType()).isEqualTo("webhook.ping");
        assertThat(row(ping)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void aHostThatNowResolvesToAPrivateAddressIsNeverCalled() throws Exception {
        WebhookDelivery delivery = due(subscribe("https://hooks.private.test/swiftdrop", SECRET),
                WebhookEventType.PARCEL_DELIVERED);

        dispatcher.dispatchDue();

        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(row(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_status_code", null).containsEntry("last_error", "URL_NOT_ALLOWED");
    }

    @Test
    void aRotatedSecretSignsTheRetry() throws Exception {
        answer.set(500);
        WebhookSubscription subscription = subscribe(hookUrl, SECRET);
        WebhookDelivery delivery = due(subscription, WebhookEventType.PARCEL_DELIVERED);
        dispatcher.dispatchDue();
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNotNull();

        subscription = subscriptions.findById(subscription.getId()).orElseThrow();
        subscription.rotateSecret(cipher.encrypt("whsec_rotated"), START);
        subscriptions.saveAndFlush(subscription);
        answer.set(200);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.dispatchDue();

        Received retry = received.poll(5, TimeUnit.SECONDS);
        assertThat(signer.matches("whsec_rotated", retry.timestamp(), retry.body(), retry.signature())).isTrue();
        assertThat(signer.matches(SECRET, retry.timestamp(), retry.body(), retry.signature())).isFalse();
        assertThat(retry.timestamp()).as("new timestamp on every attempt").isEqualTo(clock.instant().getEpochSecond());
        assertThat(row(delivery)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void oneBrokenRowDoesNotStopTheOthers() throws Exception {
        WebhookDelivery broken = due(subscribeWithCiphertext(hookUrl, "v1:not-a-real-ciphertext"),
                WebhookEventType.PARCEL_DELIVERED);
        WebhookDelivery fine = due(subscribe(hookUrl, SECRET), WebhookEventType.PARCEL_DELIVERED);

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);

        assertThat(row(fine)).containsEntry("status", "SUCCEEDED");
        // the broken one is untouched: still PENDING, no attempt counted, due again when its lease ends
        assertThat(row(broken)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(time(row(broken).get("next_attempt_at"))).isEqualTo(START.plus(WebhookDispatcher.LEASE));
        assertThat(received).hasSize(1);
    }

    @Test
    void severalDueDeliveriesAreSentInOneRound() {
        WebhookSubscription subscription = subscribe(hookUrl, SECRET);
        for (int i = 0; i < 5; i++) {
            due(subscription, WebhookEventType.PARCEL_DELIVERED);
        }

        assertThat(dispatcher.dispatchDue()).isEqualTo(5);

        assertThat(received).hasSize(5);
        assertThat(jdbc.queryForObject("select count(*) from webhooks.deliveries where merchant_id = ?"
                + " and status = 'SUCCEEDED'", Long.class, merchant)).isEqualTo(5);
    }

    // ---------- helpers ----------

    private WebhookSubscription subscribe(String url, String secret) {
        return subscribeWithCiphertext(url, cipher.encrypt(secret));
    }

    private WebhookSubscription subscribeWithCiphertext(String url, String ciphertext) {
        return subscriptions.saveAndFlush(WebhookSubscription.create(merchant, url, ciphertext,
                Set.of(WebhookEventType.PARCEL_DELIVERED), START));
    }

    private WebhookDelivery due(WebhookSubscription subscription, WebhookEventType type) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"" + type.wireName() + "\",\"data\":{}}";
        return deliveries.saveAndFlush(WebhookDelivery.pending(eventId, subscription.getId(), merchant, type, payload,
                clock.instant()));
    }

    private Map<String, Object> row(WebhookDelivery delivery) {
        return jdbc.queryForMap("select * from webhooks.deliveries where id = ?", delivery.getId());
    }

    /** timestamptz columns come back as java.sql.Timestamp or OffsetDateTime depending on the driver call. */
    private static Instant time(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
        }
        throw new IllegalArgumentException("Not a time: " + value);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
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
