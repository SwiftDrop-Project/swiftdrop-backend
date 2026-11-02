package com.swiftdrop.infrastructure.internal.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ exchange, queues and bindings. Spring AMQP declares them when the first connection opens; declaring
 * them again is harmless, so every backend instance can start with the same topology.
 *
 * <p>The delivery module (through the outbox) publishes {@code ParcelStatusChanged} to {@value #PARCEL_EVENTS_EXCHANGE}
 * with routing key {@value #PARCEL_STATUS_CHANGED}. notifications and webhooks each read their own durable queue.
 */
@Configuration
public class RabbitTopology {

    public static final String PARCEL_EVENTS_EXCHANGE = "parcel.events";
    public static final String PARCEL_STATUS_CHANGED = "parcel.status.changed";
    public static final String NOTIFICATIONS_QUEUE = "notifications.q";
    public static final String WEBHOOKS_QUEUE = "webhooks.q";

    @Bean
    Declarables parcelEventsTopology() {
        TopicExchange exchange = new TopicExchange(PARCEL_EVENTS_EXCHANGE, true, false);
        Queue notifications = QueueBuilder.durable(NOTIFICATIONS_QUEUE).build();
        Queue webhooks = QueueBuilder.durable(WEBHOOKS_QUEUE).build();
        Binding toNotifications = BindingBuilder.bind(notifications).to(exchange).with(PARCEL_STATUS_CHANGED);
        Binding toWebhooks = BindingBuilder.bind(webhooks).to(exchange).with(PARCEL_STATUS_CHANGED);
        return new Declarables(exchange, notifications, webhooks, toNotifications, toWebhooks);
    }
}
