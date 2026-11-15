
package com.swiftdrop.infrastructure.internal.messaging;

import com.swiftdrop.infrastructure.messaging.RabbitMessagingConstants;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ exchanges, queues and bindings.
 *
 * Existing parcel events topology remains unchanged.
 * WP7 adds a separate dead-letter topology for notifications.
 */
@Configuration
public class RabbitTopology {

    public static final String PARCEL_EVENTS_EXCHANGE =
            RabbitMessagingConstants.PARCEL_EVENTS_EXCHANGE;

    public static final String PARCEL_STATUS_CHANGED =
            RabbitMessagingConstants.PARCEL_STATUS_CHANGED;

    public static final String NOTIFICATIONS_QUEUE =
            RabbitMessagingConstants.NOTIFICATIONS_QUEUE;

    public static final String WEBHOOKS_QUEUE =
            RabbitMessagingConstants.WEBHOOKS_QUEUE;

    // WP7 dead-letter topology
    public static final String NOTIFICATIONS_DLX =
            RabbitMessagingConstants.NOTIFICATIONS_DLX;

    public static final String NOTIFICATIONS_DLQ =
            RabbitMessagingConstants.NOTIFICATIONS_DLQ;

    public static final String NOTIFICATIONS_DEAD_ROUTING_KEY =
            RabbitMessagingConstants.NOTIFICATIONS_DEAD_ROUTING_KEY;

    @Bean
    Declarables parcelEventsTopology() {

        TopicExchange exchange =
                new TopicExchange(PARCEL_EVENTS_EXCHANGE, true, false);

        Queue notifications =
                QueueBuilder.durable(NOTIFICATIONS_QUEUE).build();

        Queue webhooks =
                QueueBuilder.durable(WEBHOOKS_QUEUE).build();

        Binding toNotifications =
                BindingBuilder.bind(notifications)
                        .to(exchange)
                        .with(PARCEL_STATUS_CHANGED);

        Binding toWebhooks =
                BindingBuilder.bind(webhooks)
                        .to(exchange)
                        .with(PARCEL_STATUS_CHANGED);

        return new Declarables(
                exchange,
                notifications,
                webhooks,
                toNotifications,
                toWebhooks
        );
    }

    @Bean
    Declarables notificationDeadLetterTopology() {

        TopicExchange deadLetterExchange =
                new TopicExchange(NOTIFICATIONS_DLX, true, false);

        Queue deadLetterQueue =
                QueueBuilder.durable(NOTIFICATIONS_DLQ).build();

        Binding deadLetterBinding =
                BindingBuilder.bind(deadLetterQueue)
                        .to(deadLetterExchange)
                        .with(NOTIFICATIONS_DEAD_ROUTING_KEY);

        return new Declarables(
                deadLetterExchange,
                deadLetterQueue,
                deadLetterBinding
        );
    }
}
