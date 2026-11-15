package com.swiftdrop.tracking.internal.messaging;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.tracking.internal.application.LiveUpdates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * One queue per backend instance (unnamed, so RabbitMQ names it; exclusive and deleted when the instance stops) on the
 * {@code parcel.events} exchange, routing key {@code parcel.status.changed}. Every instance gets every event, because
 * each pushes live updates to the WebSocket clients connected to it. Missed events don't matter: a client loads the
 * parcel again when it reconnects.
 *
 * <p>The exchange is declared by infrastructure; declaring it again with the same settings is harmless. The names are
 * written out because another module's internal constants may not be used.
 */
@Component
class TrackingMessaging {

    static final String EXCHANGE = "parcel.events";
    static final String ROUTING_KEY = "parcel.status.changed";

    private static final Logger log = LoggerFactory.getLogger(TrackingMessaging.class);

    private final LiveUpdates liveUpdates;
    private final JsonMapper jsonMapper;

    TrackingMessaging(LiveUpdates liveUpdates, JsonMapper jsonMapper) {
        this.liveUpdates = liveUpdates;
        this.jsonMapper = jsonMapper;
    }

    /** One event. An unreadable message is dropped; a live update is never worth redelivering. */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,
            exchange = @Exchange(value = EXCHANGE, type = ExchangeTypes.TOPIC),
            key = ROUTING_KEY))
    void onParcelStatusChanged(Message message) {
        ParcelStatusChanged event;
        try {
            event = jsonMapper.readValue(message.getBody(), ParcelStatusChanged.class);
        } catch (JacksonException e) {
            log.warn("Dropped an unreadable parcel event (message id {})",
                    message.getMessageProperties().getMessageId());
            return;
        }
        try {
            liveUpdates.handle(event);
        } catch (RuntimeException e) {
            log.warn("Couldn't push the live update for {}: {}", event.trackingNumber(), e.getClass().getSimpleName());
        }
    }
}
