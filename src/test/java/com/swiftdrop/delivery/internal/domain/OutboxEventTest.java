package com.swiftdrop.delivery.internal.domain;

import com.swiftdrop.delivery.OutboxMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-06T04:00:00Z");
    private static final Instant SENT = Instant.parse("2026-11-06T04:00:05Z");
    private static final String PAYLOAD = "{\"trackingNumber\":\"SD2611004817\",\"toStatus\":\"PICKED_UP\"}";

    @Test
    void aNewEventIsNotPublishedYet() {
        OutboxEvent event = event();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.isNew()).isTrue();
        assertThat(event.getId()).isEqualTo(event.getEventId());
    }

    @Test
    void markingItPublishedTwiceKeepsTheFirstTime() {
        OutboxEvent event = event();

        event.markPublished(SENT);
        event.markPublished(SENT.plusSeconds(30));

        assertThat(event.getPublishedAt()).isEqualTo(SENT);
    }

    @Test
    void theMessageForTheRelayHasTheEventFields() {
        OutboxEvent event = event();

        OutboxMessage message = event.toMessage();

        assertThat(message.eventId()).isEqualTo(event.getEventId());
        assertThat(message.parcelId()).isEqualTo(event.getParcelId());
        assertThat(message.eventType()).isEqualTo("parcel.status.changed");
        assertThat(message.payload()).isEqualTo(PAYLOAD);
        assertThat(message.createdAt()).isEqualTo(CREATED_AT);
    }

    private static OutboxEvent event() {
        return OutboxEvent.of(UUID.randomUUID(), UUID.randomUUID(), OutboxEvent.PARCEL_STATUS_CHANGED, PAYLOAD,
                CREATED_AT);
    }
}
