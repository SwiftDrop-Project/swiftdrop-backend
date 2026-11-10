
package com.swiftdrop.outbox.internal.application;

import com.swiftdrop.delivery.DeliveryOutboxApi;
import com.swiftdrop.delivery.OutboxMessage;
import com.swiftdrop.infrastructure.internal.messaging.RabbitTopology;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
@Component
@ConditionalOnProperty(
        prefix = "swiftdrop.outbox.relay",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OutboxRelay {
    private static final Logger log =
        LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 10;
    private static final long CONFIRM_TIMEOUT_SECONDS = 10;

    private final DeliveryOutboxApi deliveryOutboxApi;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(
            DeliveryOutboxApi deliveryOutboxApi,
            RabbitTemplate rabbitTemplate) {
        this.deliveryOutboxApi = deliveryOutboxApi;
        this.rabbitTemplate = rabbitTemplate;
    }


@Scheduled(fixedDelayString = "${swiftdrop.outbox.relay.interval-ms:5000}")
public void relayBatch() {
    List<OutboxMessage> events =
            deliveryOutboxApi.fetchUnpublished(BATCH_SIZE);

    for (OutboxMessage event : events) {
        try {
            publish(event);

        } catch (Exception ex) {
            log.error(
                    "Outbox relay failed for eventId={}. Event remains eligible for retry.",
                    event.eventId(),
                    ex
            );
            break;
        }
    }
}


    private void publish(OutboxMessage event) throws Exception {
        CorrelationData correlationData =
                new CorrelationData(event.eventId().toString());

        byte[] payloadBytes = event.payload()
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

org.springframework.amqp.core.MessageProperties properties =
        new org.springframework.amqp.core.MessageProperties();

properties.setContentType("application/json");
properties.setContentEncoding("UTF-8");
properties.setMessageId(event.eventId().toString());
properties.setDeliveryMode(
        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT
);

org.springframework.amqp.core.Message message =
        new org.springframework.amqp.core.Message(payloadBytes, properties);

rabbitTemplate.send(
        RabbitTopology.PARCEL_EVENTS_EXCHANGE,
        event.eventType(),
        message,
        correlationData
);
        CorrelationData.Confirm confirm =
                correlationData.getFuture()
                        .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!confirm.isAck()) {
            throw new IllegalStateException(
                    "RabbitMQ did not confirm event " + event.eventId());
        }

        if (correlationData.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ returned unroutable event " + event.eventId());
        }

        deliveryOutboxApi.markPublished(event.eventId());
    }
}
