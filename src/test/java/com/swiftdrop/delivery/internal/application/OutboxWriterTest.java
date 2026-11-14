package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import com.swiftdrop.delivery.internal.persistence.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxWriterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final OutboxWriter writer = new OutboxWriter(outbox, jsonMapper);

    @Test
    void writesTheEventAsJsonToTheOutbox() {
        Instant now = Instant.parse("2026-11-06T04:00:00Z");
        var event = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 0, "SD2611004817",
                UUID.randomUUID(), null, null, CREATED, UUID.randomUUID(), "CUSTOMER", null, null, now, null);

        writer.write(event);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertThat(row.getEventId()).isEqualTo(event.eventId());
        assertThat(row.getParcelId()).isEqualTo(event.parcelId());
        assertThat(row.getEventType()).isEqualTo("parcel.status.changed");
        assertThat(row.getCreatedAt()).isEqualTo(now);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getPayload()).contains("\"occurredAt\":\"2026-11-06T04:00:00Z\"");
        // consumers (notifications, webhooks) read the JSON back into the same record
        assertThat(jsonMapper.readValue(row.getPayload(), ParcelStatusChanged.class)).isEqualTo(event);
    }

    @Test
    void aMerchantOrdersEventCarriesTheMerchantsOrderRef() {
        var event = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 1, "SD2611004817", null,
                UUID.randomUUID(), CREATED, PICKED_UP, UUID.randomUUID(), "COURIER", null, null,
                Instant.parse("2026-11-06T04:00:00Z"), null, "ORDER-1001");

        writer.write(event);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        String payload = saved.getValue().getPayload();
        assertThat(payload).contains("\"merchantOrderRef\":\"ORDER-1001\"");
        assertThat(jsonMapper.readValue(payload, ParcelStatusChanged.class)).isEqualTo(event);
    }

    @Test
    void anEventWrittenBeforeTheOrderRefWasAddedReadsWithNoOrderRef() {
        var event = new ParcelStatusChanged(UUID.randomUUID(), UUID.randomUUID(), 1, "SD2611004817", null,
                UUID.randomUUID(), CREATED, PICKED_UP, UUID.randomUUID(), "COURIER", null, null,
                Instant.parse("2026-11-06T04:00:00Z"), null, "ORDER-1001");
        ObjectNode older = (ObjectNode) jsonMapper.readTree(jsonMapper.writeValueAsString(event));
        older.remove("merchantOrderRef");

        ParcelStatusChanged read = jsonMapper.treeToValue(older, ParcelStatusChanged.class);

        assertThat(read.merchantOrderRef()).isNull();
        assertThat(read.trackingNumber()).isEqualTo("SD2611004817");
        assertThat(read.toStatus()).isEqualTo(PICKED_UP);
    }
}
