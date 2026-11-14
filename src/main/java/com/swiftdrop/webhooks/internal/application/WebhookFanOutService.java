package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.persistence.WebhookDeliveryRepository;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns one {@link ParcelStatusChanged} into a PENDING delivery for every active subscription of the parcel's merchant
 * that receives this event type. Nothing is sent here: the dispatch worker sends due deliveries.
 *
 * <p>Idempotent: events arrive at least once, and the unique (event, subscription) key makes a repeat add nothing.
 * Only events that happened after a subscription was created reach it, so messages waiting in webhooks.q from
 * before the subscription (e.g. when this consumer starts for the first time) are not replayed to it. Events are not
 * dropped for being older than another one of the same parcel: each is sent, and merchants order them by
 * {@code sequence}.
 */
@Service
public class WebhookFanOutService {

    private static final Logger log = LoggerFactory.getLogger(WebhookFanOutService.class);
    /** Microsecond ticks, like PostgreSQL. */
    private static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookPayloadFactory payloads;
    private final Clock clock;

    @Autowired
    public WebhookFanOutService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                                WebhookPayloadFactory payloads) {
        this(subscriptions, deliveries, payloads, DATABASE_CLOCK);
    }

    WebhookFanOutService(WebhookSubscriptionRepository subscriptions, WebhookDeliveryRepository deliveries,
                         WebhookPayloadFactory payloads, Clock clock) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.payloads = payloads;
        this.clock = clock;
    }

    /** Returns how many deliveries were added (0 for a customer parcel, no matching subscription or a repeat). */
    @Transactional
    public int handle(ParcelStatusChanged event) {
        if (event.merchantId() == null) {
            return 0; // a customer booking: no webhooks
        }
        WebhookEventType type = WebhookEventType.fromStatus(event.toStatus());
        List<WebhookSubscription> targets = subscriptions.findActiveListeningTo(event.merchantId(), type.wireName())
                .stream()
                .filter(subscription -> !subscription.getCreatedAt().isAfter(event.occurredAt()))
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }

        // built once: every subscription gets the same bytes; the event carries the merchant's order id (null if none)
        String payload = payloads.build(event, event.merchantOrderRef());
        Instant now = clock.instant();
        int added = 0;
        for (WebhookSubscription subscription : targets) {
            added += deliveries.insertIfAbsent(UUID.randomUUID(), event.eventId(), subscription.getId(),
                    event.merchantId(), type.wireName(), payload, now);
        }
        log.info("Webhook fan-out for event {} ({}): {} new deliveries", event.eventId(), type.wireName(), added);
        return added;
    }
}
