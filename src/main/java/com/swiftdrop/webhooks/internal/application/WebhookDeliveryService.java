package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.error.TooManyRequestsException;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.dto.AdminWebhookDeliveryResponse;
import com.swiftdrop.webhooks.internal.dto.WebhookDeliveryResponse;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import com.swiftdrop.webhooks.internal.security.WebhookLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Test pings, delivery history and retries (API.md "Webhooks"). Nothing is sent here: a ping or a retry makes a row
 * PENDING and due now, and {@link WebhookDispatcher} sends it within seconds.
 */
@Service
public class WebhookDeliveryService {

    static final int MAX_PAGE_SIZE = 100;
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(10);
    /** Newest first; rows of one event share createdAt, so the id keeps the order stable between pages. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    /** Microsecond ticks, like PostgreSQL, so returned times equal the stored ones. */
    private static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookPayloadFactory payloads;
    private final Clock clock;
    /** Between two test pings of one subscription, and after a delivery's last attempt before a merchant retry. */
    private final Duration cooldown;

    @Autowired
    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                                  WebhookPayloadFactory payloads, WebhookLimitsProperties limits) {
        this(deliveries, subscriptions, payloads, DATABASE_CLOCK, Duration.ofSeconds(limits.cooldownSeconds()));
    }

    /** For tests: the default 10-second cooldown. */
    WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                           WebhookPayloadFactory payloads, Clock clock) {
        this(deliveries, subscriptions, payloads, clock, DEFAULT_COOLDOWN);
    }

    WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                           WebhookPayloadFactory payloads, Clock clock, Duration cooldown) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.payloads = payloads;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    /**
     * Queues a {@code webhook.ping}, also for a turned-off subscription (so a URL can be checked before turning it
     * on). If one is still waiting to be sent, that one is returned instead of queuing another, so the button can't
     * flood the merchant's server. Otherwise a new one is queued only {@link #cooldown} after the previous one was
     * (429 with Retry-After before that): a failed ping is FAILED within seconds, and without a wait the button
     * could send to the merchant's URL again and again.
     */
    @Transactional
    public WebhookDeliveryResponse queuePing(UUID merchantId, UUID subscriptionId) {
        WebhookSubscription subscription = ownedSubscription(merchantId, subscriptionId);
        subscriptions.lockMerchant(merchantId); // two clicks at the same moment queue one ping
        String ping = WebhookEventType.WEBHOOK_PING.wireName();
        Optional<WebhookDelivery> waiting = deliveries.findFirstBySubscriptionIdAndEventTypeAndStatus(
                subscription.getId(), ping, DeliveryStatus.PENDING);
        if (waiting.isPresent()) {
            return WebhookDeliveryResponse.from(waiting.get());
        }
        Instant now = clock.instant();
        deliveries.findFirstBySubscriptionIdAndEventTypeOrderByCreatedAtDesc(subscription.getId(), ping)
                .ifPresent(previous -> requireCooldownOver(previous.getCreatedAt(), now));
        UUID eventId = UUID.randomUUID();
        WebhookDelivery delivery = deliveries.save(WebhookDelivery.pending(eventId, subscription.getId(), merchantId,
                WebhookEventType.WEBHOOK_PING, payloads.ping(eventId, subscription.getId(), now), now));
        return WebhookDeliveryResponse.from(delivery);
    }

    /**
     * One subscription's deliveries, newest first. Every filter is optional (see {@link DeliveryCriteria}: status,
     * event type, search, from/to). Only this subscription's rows are ever looked at, so a search never reaches
     * another merchant's deliveries.
     */
    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryResponse> history(UUID merchantId, UUID subscriptionId, DeliveryFilters filters,
                                                         int page, int size) {
        requireValidPage(page, size);
        DeliveryCriteria criteria = DeliveryCriteria.parse(filters);
        WebhookSubscription subscription = ownedSubscription(merchantId, subscriptionId);
        Page<WebhookDelivery> found = deliveries.findAll(criteria.toSpecification(subscription.getId(), null),
                PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.from(found, WebhookDeliveryResponse::from);
    }

    /**
     * Sends one of the merchant's FAILED deliveries again: 404 for another merchant's, 409 if it isn't FAILED, and
     * 429 with Retry-After until {@link #cooldown} after its last attempt (an old failure can be retried at once; one
     * that just failed again can't be retried in a loop). Admin retries have no cooldown.
     */
    @Transactional
    public WebhookDeliveryResponse retry(UUID merchantId, UUID deliveryId) {
        WebhookDelivery delivery = deliveries.findByIdAndMerchantId(deliveryId, merchantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (delivery.getStatus() == DeliveryStatus.FAILED) {
            requireCooldownOver(delivery.getUpdatedAt(), clock.instant());
        }
        return WebhookDeliveryResponse.from(reset(deliveryId));
    }

    /** 429 TOO_MANY_REQUESTS, with the seconds left, while {@code since + cooldown} is still in the future. */
    private void requireCooldownOver(Instant since, Instant now) {
        Instant over = since.plus(cooldown);
        if (now.isBefore(over)) {
            long waitMillis = Duration.between(now, over).toMillis();
            throw new TooManyRequestsException((waitMillis + 999) / 1000);
        }
    }

    /**
     * All of one merchant's deliveries, from every subscription (active or turned off), newest first, with the same
     * optional filters as {@link #history}. Used to show every message about one parcel ({@code search=<parcel id>}).
     * Only the merchant's own rows are ever looked at, so another merchant's parcel id finds nothing.
     */
    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryResponse> merchantList(UUID merchantId, DeliveryFilters filters, int page,
                                                              int size) {
        requireValidPage(page, size);
        DeliveryCriteria criteria = DeliveryCriteria.parse(filters);
        return PageResponse.from(
                deliveries.findAll(criteria.toSpecification(null, merchantId), PageRequest.of(page, size, NEWEST_FIRST)),
                WebhookDeliveryResponse::from);
    }

    /** Every merchant's deliveries, newest first; {@code merchantId} and every filter are optional. */
    @Transactional(readOnly = true)
    public PageResponse<AdminWebhookDeliveryResponse> adminList(UUID merchantId, DeliveryFilters filters, int page,
                                                                int size) {
        requireValidPage(page, size);
        DeliveryCriteria criteria = DeliveryCriteria.parse(filters);
        return PageResponse.from(
                deliveries.findAll(criteria.toSpecification(null, merchantId), PageRequest.of(page, size, NEWEST_FIRST)),
                AdminWebhookDeliveryResponse::from);
    }

    /** The same retry for any merchant's delivery. */
    @Transactional
    public AdminWebhookDeliveryResponse adminRetry(UUID adminId, UUID deliveryId) {
        if (!deliveries.existsById(deliveryId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        WebhookDelivery delivery = reset(deliveryId);
        log.info("Admin {} retried webhook delivery {}", adminId, deliveryId);
        return AdminWebhookDeliveryResponse.from(delivery);
    }

    /** FAILED -> PENDING in one statement; anything else is 409. Returns the row as it is now. */
    private WebhookDelivery reset(UUID deliveryId) {
        if (deliveries.resetForRetry(deliveryId, clock.instant()) == 0) {
            throw new ConflictException(ErrorCode.WEBHOOK_DELIVERY_NOT_RETRYABLE);
        }
        return deliveries.findById(deliveryId).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /** Another merchant's subscription is "not found", never "forbidden". */
    private WebhookSubscription ownedSubscription(UUID merchantId, UUID subscriptionId) {
        return subscriptions.findByIdAndMerchantId(subscriptionId, merchantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    /** Same messages as the other paged lists (e.g. GET /dispatch/assignments). */
    private static void requireValidPage(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "The page number can't be negative.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new FieldValidationException("size", "Choose a page size between 1 and 100.");
        }
    }
}
