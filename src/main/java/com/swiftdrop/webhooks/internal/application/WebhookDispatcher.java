package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.webhooks.internal.client.DeliveryOutcome;
import com.swiftdrop.webhooks.internal.client.OutgoingWebhook;
import com.swiftdrop.webhooks.internal.client.WebhookHttpSender;
import com.swiftdrop.webhooks.internal.domain.RetryPolicy;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryAttempts;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import com.swiftdrop.webhooks.internal.security.CallbackUrlValidator;
import com.swiftdrop.webhooks.internal.security.SecretCipher;
import com.swiftdrop.webhooks.internal.security.WebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends due deliveries. One round ({@link #dispatchDue()}):
 * <ol>
 *   <li>claim up to {@code batch-size} due rows (a short statement; see {@link WebhookDeliveryAttempts} for the
 *       lease),</li>
 *   <li>send them in parallel on virtual threads, with no transaction or connection held during the HTTP call
 *       (the pool has only 3 connections and a merchant may take 10 seconds),</li>
 *   <li>record each result in its own short statement.</li>
 * </ol>
 * Each attempt uses the subscription as it is now: its current URL and secret (a rotated secret applies to retries
 * too). Before each send the URL is checked again, so a host that now resolves to a private address is never called.
 *
 * <p>The schedule is {@link WebhookDispatchSchedule}; tests call {@link #dispatchDue()} directly.
 */
@Service
public class WebhookDispatcher {

    /** lastError of a parcel delivery whose subscription was turned off before it was sent. */
    public static final String SUBSCRIPTION_INACTIVE = "SUBSCRIPTION_INACTIVE";
    /** Longer than the 10-second request timeout plus the URL check, so a lease never ends mid-send. */
    static final Duration LEASE = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    /** Microsecond ticks, like PostgreSQL: the lease is compared with equality. */
    private static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private final WebhookDeliveryAttempts attempts;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final CallbackUrlValidator urlValidator;
    private final SecretCipher cipher;
    private final WebhookSigner signer;
    private final WebhookHttpSender sender;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public WebhookDispatcher(WebhookDeliveryAttempts attempts, WebhookDeliveryRepository deliveries,
                             WebhookSubscriptionRepository subscriptions, CallbackUrlValidator urlValidator,
                             SecretCipher cipher, WebhookSigner signer, WebhookHttpSender sender,
                             @Value("${swiftdrop.webhooks.dispatcher.batch-size:10}") int batchSize) {
        this(attempts, deliveries, subscriptions, urlValidator, cipher, signer, sender, batchSize, DATABASE_CLOCK);
    }

    WebhookDispatcher(WebhookDeliveryAttempts attempts, WebhookDeliveryRepository deliveries,
                      WebhookSubscriptionRepository subscriptions, CallbackUrlValidator urlValidator,
                      SecretCipher cipher, WebhookSigner signer, WebhookHttpSender sender, int batchSize, Clock clock) {
        this.attempts = attempts;
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.urlValidator = urlValidator;
        this.cipher = cipher;
        this.signer = signer;
        this.sender = sender;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /** One round. Returns how many deliveries were claimed (and attempted). */
    public int dispatchDue() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(LEASE);
        List<UUID> claimed = attempts.claimDue(now, leaseUntil, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }
        // close() waits until every attempt has finished
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID id : claimed) {
                pool.submit(() -> attemptSafely(id, leaseUntil));
            }
        }
        return claimed.size();
    }

    /** One broken row (e.g. a secret that can't be decrypted) must never stop the others. */
    private void attemptSafely(UUID id, Instant leaseUntil) {
        try {
            attempt(id, leaseUntil);
        } catch (RuntimeException ex) {
            // the row keeps its lease and is tried again when it ends; no attempt is counted
            log.error("Webhook delivery {} could not be attempted; it is tried again in {} s", id, LEASE.toSeconds(),
                    ex);
        }
    }

    private void attempt(UUID id, Instant leaseUntil) {
        Optional<WebhookDelivery> found = deliveries.findById(id);
        if (found.isEmpty()) {
            return;
        }
        WebhookDelivery delivery = found.get();
        WebhookSubscription subscription = subscriptions.findById(delivery.getSubscriptionId()).orElse(null);
        boolean ping = delivery.eventType() == WebhookEventType.WEBHOOK_PING;

        // a turned-off subscription gets no parcel events, not even ones queued before (a test ping still goes out)
        if (subscription == null || (!subscription.isActive() && !ping)) {
            if (attempts.failed(id, leaseUntil, null, SUBSCRIPTION_INACTIVE, false, clock.instant())) {
                log.info("Webhook delivery {} not sent: {}", id, SUBSCRIPTION_INACTIVE);
            }
            return;
        }

        DeliveryOutcome outcome = send(delivery, subscription);
        record(delivery, leaseUntil, outcome);
    }

    private DeliveryOutcome send(WebhookDelivery delivery, WebhookSubscription subscription) {
        if (!urlValidator.isAllowedNow(subscription.getUrl())) {
            return DeliveryOutcome.urlNotAllowed(); // nothing sent
        }
        String secret = cipher.decrypt(subscription.getSecretCiphertext());
        long timestamp = clock.instant().getEpochSecond();
        String signature = signer.sign(secret, timestamp, delivery.getPayload());
        return sender.send(new OutgoingWebhook(subscription.getUrl(), delivery.getEventId(),
                delivery.eventType().wireName(), timestamp, signature, delivery.getPayload()));
    }

    private void record(WebhookDelivery delivery, Instant leaseUntil, DeliveryOutcome outcome) {
        UUID id = delivery.getId();
        int attempt = delivery.getAttempts() + 1;
        Instant now = clock.instant();
        boolean recorded;
        String result;
        if (outcome.succeeded()) {
            recorded = attempts.succeeded(id, leaseUntil, outcome.statusCode(), now);
            result = "SUCCEEDED";
        } else {
            Optional<Instant> next = outcome.retryable() ? RetryPolicy.nextAttemptAt(attempt, now) : Optional.empty();
            if (next.isPresent()) {
                recorded = attempts.retryLater(id, leaseUntil, outcome.statusCode(), outcome.error(), next.get(), now);
                result = outcome.error() + ", next attempt at " + next.get();
            } else {
                recorded = attempts.failed(id, leaseUntil, outcome.statusCode(), outcome.error(), true, now);
                result = outcome.error() + ", FAILED";
            }
        }
        // never the URL (it may hold a token), the body or the secret
        if (recorded) {
            log.info("Webhook delivery {} attempt {}: {}", id, attempt, result);
        } else {
            log.debug("Webhook delivery {} attempt {} ({}): lease lost, result not recorded", id, attempt,
                    outcome.label());
        }
    }
}
