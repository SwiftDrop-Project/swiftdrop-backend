package com.swiftdrop.dispatch.internal.messaging;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.dispatch.internal.application.ParcelEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dispatch's own durable queue {@value #QUEUE} on the {@code parcel.events} exchange (routing key
 * {@code parcel.status.changed}, published by the outbox relay), used to keep the pickup queue up to date.
 *
 * <p>The exchange is declared by infrastructure; this only declares the queue and its binding (declaring them
 * again on every start is harmless). The names are written out here because another module's internal constants
 * may not be used.
 */
@Configuration
class DispatchMessaging {

    static final String QUEUE = "dispatch.q";
    static final String EXCHANGE = "parcel.events";
    static final String ROUTING_KEY = "parcel.status.changed";

    private static final Logger log = LoggerFactory.getLogger(DispatchMessaging.class);

    private final ParcelEventHandler handler;
    private final JsonMapper jsonMapper;

    DispatchMessaging(ParcelEventHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    @Bean
    Declarables dispatchTopology() {
        Queue queue = QueueBuilder.durable(QUEUE).build();
        Binding binding = new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTING_KEY, null);
        return new Declarables(queue, binding);
    }

    /**
     * One event. A message that can't be read is logged and dropped (redelivering it would fail the same way);
     * a failure while handling it is thrown, so RabbitMQ redelivers it.
     */
    @RabbitListener(queues = QUEUE)
    void onParcelStatusChanged(Message message) {
        ParcelStatusChanged event;
        try {
            event = jsonMapper.readValue(message.getBody(), ParcelStatusChanged.class);
        } catch (JacksonException e) {
            log.warn("Dropped an unreadable parcel event (message id {})",
                    message.getMessageProperties().getMessageId());
            return;
        }
        handler.handle(event);
    }
}
