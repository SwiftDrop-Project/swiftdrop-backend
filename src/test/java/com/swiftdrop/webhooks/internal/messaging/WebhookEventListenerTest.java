package com.swiftdrop.webhooks.internal.messaging;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real path: a message on exchange parcel.events (as the outbox relay publishes it) reaches webhooks.q and
 * becomes PENDING deliveries. The listener runs on its own thread, so the tests wait for the rows.
 */
class WebhookEventListenerTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anEventPublishedToTheExchangeBecomesAPendingDelivery() throws Exception {
        UUID merchant = subscribedMerchant();
        ParcelStatusChanged event = delivered(merchant);

        publish(json.writeValueAsBytes(event), event.eventId().toString());

        assertThat(waitForDeliveries(event.eventId(), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from webhooks.deliveries where event_id = ?", String.class,
                event.eventId())).isEqualTo("PENDING");
    }

    @Test
    void aRedeliveredMessageAddsNothing() throws Exception {
        UUID merchant = subscribedMerchant();
        ParcelStatusChanged event = delivered(merchant);
        byte[] body = json.writeValueAsBytes(event);

        publish(body, event.eventId().toString());
        waitForDeliveries(event.eventId(), 1);
        publish(body, event.eventId().toString());
        // a later marker event proves the repeat has been handled by now (one consumer, in order)
        ParcelStatusChanged marker = delivered(merchant);
        publish(json.writeValueAsBytes(marker), marker.eventId().toString());
        waitForDeliveries(marker.eventId(), 1);

        assertThat(count(event.eventId())).isEqualTo(1);
    }

    @Test
    void anUnreadableMessageIsDroppedAndDoesNotBlockTheQueue() throws Exception {
        UUID merchant = subscribedMerchant();
        ParcelStatusChanged event = delivered(merchant);

        publish("not json".getBytes(StandardCharsets.UTF_8), "broken-message");
        publish(json.writeValueAsBytes(event), event.eventId().toString());

        assertThat(waitForDeliveries(event.eventId(), 1)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private UUID subscribedMerchant() {
        UUID merchant = UUID.randomUUID();
        subscriptions.saveAndFlush(WebhookSubscription.create(merchant, "https://shop.example.com/hook",
                "v1:not-used-here", Set.of(WebhookEventType.PARCEL_DELIVERED),
                Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS)));
        return merchant;
    }

    private static ParcelStatusChanged delivered(UUID merchant) {
        return new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 4, "SD2611000001", null, merchant,
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, UUID.randomUUID(), "COURIER", null, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS), null);
    }

    /** Like the outbox relay: JSON bytes, persistent, message id = event id. */
    private void publish(byte[] body, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(messageId);
        rabbit.send("parcel.events", "parcel.status.changed", new Message(body, properties));
    }

    private long waitForDeliveries(UUID eventId, long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        long found = count(eventId);
        while (found < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            found = count(eventId);
        }
        return found;
    }

    private long count(UUID eventId) {
        return jdbc.queryForObject("select count(*) from webhooks.deliveries where event_id = ?", Long.class, eventId);
    }
}
