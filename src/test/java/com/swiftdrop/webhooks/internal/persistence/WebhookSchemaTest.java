package com.swiftdrop.webhooks.internal.persistence;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;
import com.swiftdrop.webhooks.internal.domain.WebhookDelivery;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_DELIVERED;
import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_FAILED;
import static com.swiftdrop.webhooks.internal.domain.WebhookEventType.PARCEL_PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The webhooks migration against the real PostgreSQL (Testcontainers): the entities match the tables (ddl-auto
 * validate already proves the columns), the constraints hold, and the repository queries work.
 * Every test uses fresh merchant ids, so tests don't see each other's rows.
 */
class WebhookSchemaTest extends IntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String URL = "https://shop.example.com/swiftdrop";
    private static final String PAYLOAD =
            "{\"id\":\"e1\",\"type\":\"parcel.delivered\",\"createdAt\":\"2026-11-13T09:15:00Z\",\"data\":{\"b\":1,  \"a\":2}}";

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    // ---------- subscriptions ----------

    @Test
    void aSubscriptionIsSavedAndReadBack() {
        UUID merchant = UUID.randomUUID();
        WebhookSubscription saved = subscriptions.saveAndFlush(
                subscription(merchant, EnumSet.of(PARCEL_FAILED, PARCEL_DELIVERED)));

        WebhookSubscription read = subscriptions.findById(saved.getId()).orElseThrow();

        assertThat(read.getMerchantId()).isEqualTo(merchant);
        assertThat(read.getUrl()).isEqualTo(URL);
        assertThat(read.getSecretCiphertext()).isEqualTo("encrypted-secret");
        assertThat(read.eventTypes()).containsExactlyInAnyOrder(PARCEL_DELIVERED, PARCEL_FAILED);
        assertThat(read.isActive()).isTrue();
        assertThat(read.getCreatedAt()).isEqualTo(NOW);
        assertThat(read.getUpdatedAt()).isEqualTo(NOW);
        assertThat(read.getVersion()).isZero();
        // stored as wire names, in a fixed order
        assertThat(jdbc.queryForObject("select array_to_string(event_types, ',') from webhooks.subscriptions"
                + " where id = ?", String.class, saved.getId())).isEqualTo("parcel.delivered,parcel.failed");
    }

    @Test
    void deactivatingRaisesTheVersionAndKeepsTheRow() {
        UUID id = subscriptions.saveAndFlush(subscription(UUID.randomUUID(), Set.of(PARCEL_DELIVERED))).getId();

        transactions.executeWithoutResult(status ->
                subscriptions.findById(id).orElseThrow().deactivate(NOW.plusSeconds(60)));
        transactions.executeWithoutResult(status ->           // a second time changes nothing
                subscriptions.findById(id).orElseThrow().deactivate(NOW.plusSeconds(120)));

        WebhookSubscription read = subscriptions.findById(id).orElseThrow();
        assertThat(read.isActive()).isFalse();
        assertThat(read.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(read.getVersion()).isEqualTo(1);
        assertThat(read.listensTo(PARCEL_DELIVERED)).isFalse();
    }

    @Test
    void fanOutFindsOnlyActiveSubscriptionsOfTheMerchantThatListenToTheEvent() {
        UUID merchant = UUID.randomUUID();
        WebhookSubscription matching = subscriptions.saveAndFlush(
                subscription(merchant, Set.of(PARCEL_DELIVERED, PARCEL_FAILED)));
        subscriptions.saveAndFlush(subscription(merchant, Set.of(PARCEL_PICKED_UP)));          // other event
        WebhookSubscription inactive = subscription(merchant, Set.of(PARCEL_DELIVERED));
        inactive.deactivate(NOW);
        subscriptions.saveAndFlush(inactive);
        subscriptions.saveAndFlush(subscription(UUID.randomUUID(), Set.of(PARCEL_DELIVERED))); // other merchant

        assertThat(subscriptions.findActiveListeningTo(merchant, PARCEL_DELIVERED.wireName()))
                .extracting(WebhookSubscription::getId).containsExactly(matching.getId());
        assertThat(subscriptions.findActiveListeningTo(merchant, "parcel.in_transit")).isEmpty();
        assertThat(subscriptions.countByMerchantIdAndActiveTrue(merchant)).isEqualTo(2);
    }

    @Test
    void ownershipLookupIgnoresOtherMerchantsAndTheListIsNewestFirst() {
        UUID merchant = UUID.randomUUID();
        WebhookSubscription older = subscriptions.saveAndFlush(WebhookSubscription.create(merchant, URL, "s",
                Set.of(PARCEL_DELIVERED), NOW.minusSeconds(60)));
        WebhookSubscription newer = subscriptions.saveAndFlush(subscription(merchant, Set.of(PARCEL_FAILED)));

        assertThat(subscriptions.findByIdAndMerchantId(older.getId(), merchant)).isPresent();
        assertThat(subscriptions.findByIdAndMerchantId(older.getId(), UUID.randomUUID())).isEmpty();
        assertThat(subscriptions.findByMerchantIdOrderByCreatedAtDesc(merchant))
                .extracting(WebhookSubscription::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void eventTypesMustNotBeEmpty() {
        assertThatThrownBy(() -> insertSubscription("{}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_subscriptions_event_types_not_empty");
    }

    @Test
    void eventTypesMustBeKnownParcelEvents() {
        assertThatThrownBy(() -> insertSubscription("{parcel.lost}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_subscriptions_event_types_known");
        assertThatThrownBy(() -> insertSubscription("{webhook.ping}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_subscriptions_event_types_known");
    }

    // ---------- deliveries ----------

    @Test
    void aDeliveryIsSavedAndReadBackWithTheExactPayload() {
        UUID merchant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        WebhookDelivery saved = deliveries.saveAndFlush(
                WebhookDelivery.pending(event, subscription, merchant, PARCEL_DELIVERED, PAYLOAD, NOW));

        WebhookDelivery read = deliveries.findById(saved.getId()).orElseThrow();

        assertThat(read.getEventId()).isEqualTo(event);
        assertThat(read.getSubscriptionId()).isEqualTo(subscription);
        assertThat(read.getMerchantId()).isEqualTo(merchant);
        assertThat(read.eventType()).isEqualTo(PARCEL_DELIVERED);
        // TEXT keeps key order and spacing (JSONB would not): retries send the same bytes
        assertThat(read.getPayload()).isEqualTo(PAYLOAD);
        assertThat(read.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(read.getAttempts()).isZero();
        assertThat(read.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(read.getLastStatusCode()).isNull();
        assertThat(read.getLastError()).isNull();
        assertThat(read.getDeliveredAt()).isNull();
    }

    @Test
    void theSameEventIsStoredOnlyOncePerSubscription() {
        UUID merchant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        deliveries.saveAndFlush(WebhookDelivery.pending(event, subscription, merchant, PARCEL_DELIVERED, PAYLOAD, NOW));

        assertThatThrownBy(() -> deliveries.saveAndFlush(
                WebhookDelivery.pending(event, subscription, merchant, PARCEL_DELIVERED, PAYLOAD, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_deliveries_event_subscription");
    }

    @Test
    void theSameEventMayGoToSeveralSubscriptions() {
        UUID merchant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        deliveries.saveAndFlush(WebhookDelivery.pending(event, UUID.randomUUID(), merchant, PARCEL_DELIVERED,
                PAYLOAD, NOW));
        deliveries.saveAndFlush(WebhookDelivery.pending(event, UUID.randomUUID(), merchant, PARCEL_DELIVERED,
                PAYLOAD, NOW));

        assertThat(jdbc.queryForObject("select count(*) from webhooks.deliveries where event_id = ?", Long.class,
                event)).isEqualTo(2);
    }

    @Test
    void aPendingDeliveryAlwaysHasANextAttempt() {
        assertThatThrownBy(() -> insertDelivery("PENDING", "parcel.delivered", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_deliveries_next_attempt");
        assertThatThrownBy(() -> insertDelivery("FAILED", "parcel.delivered", NOW, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_deliveries_next_attempt");
    }

    @Test
    void onlyASucceededDeliveryHasADeliveredTime() {
        assertThatThrownBy(() -> insertDelivery("SUCCEEDED", "parcel.delivered", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_deliveries_delivered");
        insertDelivery("SUCCEEDED", "parcel.delivered", null, NOW); // allowed
    }

    @Test
    void deliveryStatusAndEventTypeAreChecked() {
        assertThatThrownBy(() -> insertDelivery("SENT", "parcel.delivered", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_deliveries_status");
        assertThatThrownBy(() -> insertDelivery("PENDING", "parcel.lost", NOW, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_deliveries_event_type");
        insertDelivery("PENDING", "webhook.ping", NOW, null); // the ping is a valid delivery
    }

    @Test
    void historyIsNewestFirstAndPaged() {
        UUID merchant = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            deliveries.saveAndFlush(WebhookDelivery.pending(UUID.randomUUID(), subscription, merchant,
                    PARCEL_DELIVERED, PAYLOAD, NOW.plusSeconds(i)));
        }

        Page<WebhookDelivery> first = deliveries.findBySubscriptionIdOrderByCreatedAtDesc(subscription,
                PageRequest.of(0, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(WebhookDelivery::getCreatedAt)
                .containsExactly(NOW.plusSeconds(2), NOW.plusSeconds(1));
    }

    // ---------- helpers ----------

    private static WebhookSubscription subscription(UUID merchant, Set<WebhookEventType> types) {
        return WebhookSubscription.create(merchant, URL, "encrypted-secret", types, NOW);
    }

    private void insertSubscription(String eventTypesLiteral) {
        jdbc.update("""
                insert into webhooks.subscriptions
                    (id, merchant_id, url, secret_ciphertext, event_types, active, created_at, updated_at, version)
                values (?, ?, ?, 's', ?::text[], true, now(), now(), 0)""",
                UUID.randomUUID(), UUID.randomUUID(), URL, eventTypesLiteral);
    }

    private void insertDelivery(String status, String eventType, Instant nextAttemptAt, Instant deliveredAt) {
        jdbc.update("""
                insert into webhooks.deliveries
                    (id, event_id, subscription_id, merchant_id, event_type, payload, status, attempts,
                     next_attempt_at, created_at, updated_at, delivered_at)
                values (?, ?, ?, ?, ?, '{}', ?, 0, ?, now(), now(), ?)""",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), eventType, status,
                ts(nextAttemptAt), ts(deliveredAt));
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}
