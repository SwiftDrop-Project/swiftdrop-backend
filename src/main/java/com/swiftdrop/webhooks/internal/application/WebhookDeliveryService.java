package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.web.PageResponse;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.dto.AdminWebhookDeliveryResponse;
import com.swiftdrop.webhooks.internal.dto.WebhookDeliveryResponse;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test pings, delivery history and retries (API.md "Webhooks"). Nothing is sent here: a ping or a retry makes a row
 * PENDING and due now, and {@link WebhookDispatcher} sends it within seconds.
 */
@Service
public class WebhookDeliveryService {

    static final int MAX_PAGE_SIZE = 100;
    /** Newest first; rows of one event share createdAt, so the id keeps the order stable between pages. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    /** Microsecond ticks, like PostgreSQL, so returned times equal the stored ones. */
    private static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookPayloadFactory payloads;
    private final Clock clock;

    @Autowired
    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                                  WebhookPayloadFactory payloads) {
        this(deliveries, subscriptions, payloads, DATABASE_CLOCK);
    }

    WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                           WebhookPayloadFactory payloads, Clock clock) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.payloads = payloads;
        this.clock = clock;
    }

    /**
     * Queues a {@code webhook.ping}, also for a turned-off subscription (so a URL can be checked before turning it
     * on). If one is still waiting to be sent, that one is returned instead of queuing another, so the button can't
     * flood the merchant's server.
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
        UUID eventId = UUID.randomUUID();
        WebhookDelivery delivery = deliveries.save(WebhookDelivery.pending(eventId, subscription.getId(), merchantId,
                WebhookEventType.WEBHOOK_PING, payloads.ping(eventId, subscription.getId(), now), now));
        return WebhookDeliveryResponse.from(delivery);
    }

    /** One subscription's deliveries, newest first; {@code status} null means all. */
    @Transactional(readOnly = true)
    public PageResponse<WebhookDeliveryResponse> history(UUID merchantId, UUID subscriptionId, DeliveryStatus status,
                                                         int page, int size) {
        requireValidPage(page, size);
        WebhookSubscription subscription = ownedSubscription(merchantId, subscriptionId);
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        Page<WebhookDelivery> found = status == null
                ? deliveries.findBySubscriptionId(subscription.getId(), request)
                : deliveries.findBySubscriptionIdAndStatus(subscription.getId(), status, request);
        return PageResponse.from(found, WebhookDeliveryResponse::from);
    }

    /** Sends one of the merchant's FAILED deliveries again: 404 for another merchant's, 409 if it isn't FAILED. */
    @Transactional
    public WebhookDeliveryResponse retry(UUID merchantId, UUID deliveryId) {
        deliveries.findByIdAndMerchantId(deliveryId, merchantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return WebhookDeliveryResponse.from(reset(deliveryId));
    }

    /** Every merchant's deliveries, newest first; both filters are optional. */
    @Transactional(readOnly = true)
    public PageResponse<AdminWebhookDeliveryResponse> adminList(DeliveryStatus status, UUID merchantId, int page,
                                                                int size) {
        requireValidPage(page, size);
        Specification<WebhookDelivery> filters = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (status != null) {
                conditions.add(cb.equal(root.get("status"), status));
            }
            if (merchantId != null) {
                conditions.add(cb.equal(root.get("merchantId"), merchantId));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
        return PageResponse.from(deliveries.findAll(filters, PageRequest.of(page, size, NEWEST_FIRST)),
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
