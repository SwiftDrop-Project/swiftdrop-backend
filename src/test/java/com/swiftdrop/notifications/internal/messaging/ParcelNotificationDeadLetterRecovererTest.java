
package com.swiftdrop.notifications.internal.messaging;

import com.swiftdrop.infrastructure.internal.messaging.RabbitTopology;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParcelNotificationDeadLetterRecovererTest {

    private RabbitTemplate rabbitTemplate;
    private ParcelNotificationDeadLetterRecoverer recoverer;
    private Message message;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);

        recoverer =
                new ParcelNotificationDeadLetterRecoverer(
                        rabbitTemplate
                );

        message = new Message(
                "{}".getBytes(StandardCharsets.UTF_8),
                new MessageProperties()
        );
    }

    @Test
    void successfulPublishDoesNotThrow() {

        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);

            data.getFuture().complete(
                    new CorrelationData.Confirm(true, null)
            );

            return null;
        }).when(rabbitTemplate).send(
                eq(RabbitTopology.NOTIFICATIONS_DLX),
                eq(RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY),
                same(message),
                any(CorrelationData.class)
        );

        assertDoesNotThrow(
                () -> recoverer.recover(
                        message,
                        new RuntimeException("Processing failed")
                )
        );

        verify(rabbitTemplate).send(
                eq(RabbitTopology.NOTIFICATIONS_DLX),
                eq(RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY),
                same(message),
                any(CorrelationData.class)
        );
    }

    @Test
    void brokerNackTriggersRequeue() {

        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);

            data.getFuture().complete(
                    new CorrelationData.Confirm(false, "Broker NACK")
            );

            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        assertThrows(
                ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(
                        message,
                        new RuntimeException("Processing failed")
                )
        );
    }

    @Test
    void publishExceptionTriggersRequeue() {

        doThrow(new IllegalStateException("Publish failed"))
                .when(rabbitTemplate).send(
                        anyString(),
                        anyString(),
                        any(Message.class),
                        any(CorrelationData.class)
                );

        assertThrows(
                ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(
                        message,
                        new RuntimeException("Processing failed")
                )
        );
    }

    @Test
    void nullMessageTriggersRequeue() {

        assertThrows(
                ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(
                        null,
                        new RuntimeException("Processing failed")
                )
        );

        verifyNoInteractions(rabbitTemplate);
    }

@Test
void unroutableMessageTriggersRequeue() {

    doAnswer(invocation -> {
        CorrelationData data = invocation.getArgument(3);

        org.springframework.amqp.core.ReturnedMessage returned =
                new org.springframework.amqp.core.ReturnedMessage(
                        message,
                        312,
                        "NO_ROUTE",
                        RabbitTopology.NOTIFICATIONS_DLX,
                        RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY
                );

        data.setReturned(returned);

        data.getFuture().complete(
                new CorrelationData.Confirm(true, null)
        );

        return null;
    }).when(rabbitTemplate).send(
            eq(RabbitTopology.NOTIFICATIONS_DLX),
            eq(RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY),
            same(message),
            any(CorrelationData.class)
    );

    assertThrows(
            ImmediateRequeueAmqpException.class,
            () -> recoverer.recover(
                    message,
                    new RuntimeException("Processing failed")
            )
    );

    verify(rabbitTemplate).send(
            eq(RabbitTopology.NOTIFICATIONS_DLX),
            eq(RabbitTopology.NOTIFICATIONS_DEAD_ROUTING_KEY),
            same(message),
            any(CorrelationData.class)
    );
}

}
