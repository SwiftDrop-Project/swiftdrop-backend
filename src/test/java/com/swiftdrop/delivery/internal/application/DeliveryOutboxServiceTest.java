package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.OutboxMessage;
import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import com.swiftdrop.delivery.internal.persistence.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");

    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final DeliveryOutboxService service = new DeliveryOutboxService(events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fetchUnpublishedGivesTheOldestEventsUpToTheLimit() {
        OutboxEvent older = anEvent(NOW.minusSeconds(60));
        OutboxEvent newer = anEvent(NOW.minusSeconds(30));
        when(events.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(2))).thenReturn(List.of(older, newer));

        List<OutboxMessage> messages = service.fetchUnpublished(2);

        assertThat(messages).extracting(OutboxMessage::eventId).containsExactly(older.getEventId(), newer.getEventId());
        assertThat(messages.get(0).eventType()).isEqualTo(OutboxEvent.PARCEL_STATUS_CHANGED);
        assertThat(messages.get(0).payload()).isEqualTo("{\"toStatus\":\"CREATED\"}");
    }

    @Test
    void aLimitOfZeroOrLessIsABug() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.fetchUnpublished(0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.fetchUnpublished(-5));

        verifyNoInteractions(events);
    }

    @Test
    void markPublishedKeepsTheFirstTime() {
        OutboxEvent event = anEvent(NOW.minusSeconds(60));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));

        service.markPublished(event.getEventId());
        assertThat(event.getPublishedAt()).isEqualTo(NOW);

        // a second confirmation (the relay retried) changes nothing
        new DeliveryOutboxService(events, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC))
                .markPublished(event.getEventId());
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void markPublishedIgnoresAnUnknownEvent() {
        UUID unknown = UUID.randomUUID();
        when(events.findById(unknown)).thenReturn(Optional.empty());

        service.markPublished(unknown);
        // no exception: nothing to mark
    }

    @Test
    void theRealClockKeepsWholeMicrosecondsLikePostgres() {
        assertThat(DeliveryService.DATABASE_CLOCK.instant().getNano() % 1_000).isZero();
    }

    private static OutboxEvent anEvent(Instant createdAt) {
        return OutboxEvent.of(UUID.randomUUID(), UUID.randomUUID(), OutboxEvent.PARCEL_STATUS_CHANGED,
                "{\"toStatus\":\"CREATED\"}", createdAt);
    }
}
