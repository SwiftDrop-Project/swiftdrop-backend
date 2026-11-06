package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.delivery.internal.domain.OutboxEvent;
import com.swiftdrop.delivery.internal.persistence.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a ParcelStatusChanged event to delivery's outbox, as JSON. It must run inside the caller's transaction
 * (MANDATORY), so the event is saved together with the parcel change or not at all: that is what makes the outbox
 * reliable. The outbox relay publishes it to RabbitMQ later.
 */
@Component
class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final JsonMapper jsonMapper;

    OutboxWriter(OutboxEventRepository outbox, JsonMapper jsonMapper) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(ParcelStatusChanged event) {
        String payload = jsonMapper.writeValueAsString(event);
        outbox.save(OutboxEvent.of(event.eventId(), event.parcelId(), OutboxEvent.PARCEL_STATUS_CHANGED, payload,
                event.occurredAt()));
    }
}
