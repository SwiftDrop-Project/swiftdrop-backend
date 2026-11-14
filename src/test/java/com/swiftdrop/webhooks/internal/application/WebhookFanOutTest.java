package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_DELIVERED;
import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_FAILED;
import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fan-out against the real database, called directly (the RabbitMQ path is WebhookEventListenerTest).
 * Subscriptions are saved through the repository; every test uses its own merchant and event ids.
 */
class WebhookFanOutTest extends IntegrationTest {

    /** Subscriptions are created "now"; events happen a little later, so they reach them. */
    private static final Instant SUBSCRIBED = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant LATER = SUBSCRIBED.plusSeconds(60);

    @Autowired
    WebhookFanOutService fanOut;

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyMatchingSubscriptionGetsOnePendingDelivery() {
        UUID merchant = UUID.randomUUID();
        UUID first = subscribe(merchant, Set.of(PARCEL_DELIVERED, PARCEL_FAILED));
        UUID second = subscribe(merchant, Set.of(PARCEL_DELIVERED));
        ParcelStatusChanged event = delivered(merchant);

        assertThat(fanOut.handle(event)).isEqualTo(2);

        List<Map<String, Object>> rows = rows(event.eventId());
        assertThat(rows).extracting(row -> row.get("subscription_id")).containsExactlyInAnyOrder(first, second);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("merchant_id")).isEqualTo(merchant);
            assertThat(row.get("event_type")).isEqualTo("parcel.delivered");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(row.get("next_attempt_at")).isNotNull();
            assertThat(row.get("delivered_at")).isNull();
        });
    }

    @Test
    void theSameEventTwiceAddsNothingMore() {
        UUID merchant = UUID.randomUUID();
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        ParcelStatusChanged event = delivered(merchant);

        assertThat(fanOut.handle(event)).isEqualTo(2);
        assertThat(fanOut.handle(event)).isZero();

        assertThat(rows(event.eventId())).hasSize(2);
    }

    @Test
    void onlyActiveSubscriptionsOfThisMerchantForThisEventTypeAreUsed() {
        UUID merchant = UUID.randomUUID();
        UUID matching = subscribe(merchant, Set.of(PARCEL_DELIVERED));
        subscribe(merchant, Set.of(PARCEL_PICKED_UP));                 // other event type
        WebhookSubscription off = WebhookSubscription.create(merchant, "https://shop.example.com/off", "v1:x",
                Set.of(PARCEL_DELIVERED), SUBSCRIBED);
        off.deactivate(SUBSCRIBED);
        subscriptions.saveAndFlush(off);                                // turned off
        subscribe(UUID.randomUUID(), Set.of(PARCEL_DELIVERED));         // other merchant
        ParcelStatusChanged event = delivered(merchant);

        assertThat(fanOut.handle(event)).isEqualTo(1);

        assertThat(rows(event.eventId())).extracting(row -> row.get("subscription_id")).containsExactly(matching);
    }

    @Test
    void customerParcelsNeverTriggerWebhooks() {
        ParcelStatusChanged event = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 3, "SD2611000001",
                UUID.randomUUID(), null, ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, UUID.randomUUID(),
                "COURIER", null, null, LATER, null);

        assertThat(fanOut.handle(event)).isZero();
        assertThat(rows(event.eventId())).isEmpty();
    }

    @Test
    void eventsFromBeforeASubscriptionWasCreatedAreNotReplayedToIt() {
        UUID merchant = UUID.randomUUID();
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        ParcelStatusChanged old = event(merchant, UUID.randomUUID(), ParcelStatus.DELIVERED,
                SUBSCRIBED.minusSeconds(3600));

        assertThat(fanOut.handle(old)).isZero();
        assertThat(rows(old.eventId())).isEmpty();
    }

    @Test
    void everySubscriptionGetsTheSameBody() {
        UUID merchant = UUID.randomUUID();
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        ParcelStatusChanged event = delivered(merchant);

        fanOut.handle(event);

        List<Object> payloads = rows(event.eventId()).stream().map(row -> row.get("payload")).toList();
        assertThat(payloads).hasSize(2).containsOnly(payloads.getFirst());
        assertThat((String) payloads.getFirst())
                .startsWith("{\"id\":\"" + event.eventId() + "\",\"type\":\"parcel.delivered\",")
                .contains("\"sequence\":4")
                .doesNotContain("customerId").doesNotContain("note").doesNotContain("actorId");
    }

    @Test
    void theOrderReferenceComesFromTheEvent() {
        UUID merchant = UUID.randomUUID();
        subscribe(merchant, Set.of(WebhookEventType.PARCEL_CREATED));
        // delivery puts the merchant's order id into the event: no parcel has to exist for the webhook
        ParcelStatusChanged created = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 0,
                "SD2611000042", null, merchant, null, ParcelStatus.CREATED, merchant, "MERCHANT", null, null,
                LATER, null, "TECH-ORD-1001");

        assertThat(fanOut.handle(created)).isEqualTo(1);

        assertThat((String) rows(created.eventId()).getFirst().get("payload"))
                .contains("\"merchantOrderRef\":\"TECH-ORD-1001\"")
                .contains("\"trackingNumber\":\"SD2611000042\"")
                .contains("\"previousStatus\":null");
    }

    @Test
    void anUnknownParcelHasNoOrderReference() {
        UUID merchant = UUID.randomUUID();
        subscribe(merchant, Set.of(PARCEL_DELIVERED));
        ParcelStatusChanged event = delivered(merchant); // random parcel id: not in delivery

        fanOut.handle(event);

        assertThat((String) rows(event.eventId()).getFirst().get("payload")).contains("\"merchantOrderRef\":null");
    }

    // ---------- helpers ----------

    private UUID subscribe(UUID merchant, Set<WebhookEventType> types) {
        return subscriptions.saveAndFlush(WebhookSubscription.create(merchant, "https://shop.example.com/hook",
                "v1:not-used-here", types, SUBSCRIBED)).getId();
    }

    private static ParcelStatusChanged delivered(UUID merchant) {
        return event(merchant, UUID.randomUUID(), ParcelStatus.DELIVERED, LATER);
    }

    private static ParcelStatusChanged event(UUID merchant, UUID parcel, ParcelStatus to, Instant occurredAt) {
        return new ParcelStatusChanged(UUID.randomUUID(), parcel, 4, "SD2611000001", null, merchant,
                ParcelStatus.OUT_FOR_DELIVERY, to, UUID.randomUUID(), "COURIER", "Left with neighbour", null,
                occurredAt, null);
    }

    private List<Map<String, Object>> rows(UUID eventId) {
        return jdbc.queryForList("select * from webhooks.deliveries where event_id = ?", eventId);
    }
}
