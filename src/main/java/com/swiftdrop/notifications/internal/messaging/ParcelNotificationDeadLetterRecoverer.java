
package com.swiftdrop.notifications.internal.messaging;

import com.swiftdrop.infrastructure.internal.messaging.RabbitTopology;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ParcelNotificationDeadLetterRecoverer {

    private final RabbitTemplate rabbitTemplate;

    public ParcelNotificationDeadLetterRecoverer(
            RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void recover(Message originalMessage, Throwable cause) {

        if (originalMessage == null) {
            throw new ImmediateRequeueAmqpException(
                    "Original AMQP message unavailable",
                    cause
            );
        }

        CorrelationData correlationData =
                new CorrelationData(UUID.randomUUID().toString());

        try {
            rabbitTemplate.send(
                    RabbitTopology.NOTIFICATIONS_DLX,
                    RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY,
                    originalMessage,
                    correlationData
            );

            CorrelationData.Confirm confirm =
                    correlationData.getFuture()
                            .get(10, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                throw new IllegalStateException(
                        "DLQ publish was not acknowledged"
                );
            }

            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "DLQ message was unroutable"
                );
            }

        } catch (Exception ex) {
            throw new ImmediateRequeueAmqpException(
                    "DLQ recovery failed; requeue original",
                    ex
            );
        }
    }
}
