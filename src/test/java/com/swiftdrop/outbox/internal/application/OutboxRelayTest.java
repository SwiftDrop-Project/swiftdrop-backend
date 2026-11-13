
package com.swiftdrop.outbox.internal.application;

import com.swiftdrop.delivery.DeliveryOutboxApi;
import com.swiftdrop.delivery.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    @Test
    void doesNothingWhenNoEventsExist() {
        DeliveryOutboxApi outboxApi = mock(DeliveryOutboxApi.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        when(outboxApi.fetchUnpublished(10))
                .thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(outboxApi, rabbitTemplate);

        relay.relayBatch();

        verify(outboxApi).fetchUnpublished(10);
        verifyNoInteractions(rabbitTemplate);
        verify(outboxApi, never()).markPublished(any());
    }

    @Test
    void doesNotMarkPublishedWhenRabbitMqFails() {
        DeliveryOutboxApi outboxApi = mock(DeliveryOutboxApi.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        OutboxMessage event = new OutboxMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "parcel.status.changed",
                "{\"status\":\"IN_TRANSIT\"}",
                Instant.now()
        );

        when(outboxApi.fetchUnpublished(10))
                .thenReturn(List.of(event));

        doThrow(new RuntimeException("Simulated RabbitMQ failure"))
                .when(rabbitTemplate)
                .send(
                        anyString(),
                        anyString(),
                        any(Message.class),
                        any(CorrelationData.class)
                );

        OutboxRelay relay = new OutboxRelay(outboxApi, rabbitTemplate);

        relay.relayBatch();

        verify(rabbitTemplate).send(
                eq("parcel.events"),
                eq("parcel.status.changed"),
                any(Message.class),
                any(CorrelationData.class)
        );

        verify(outboxApi, never()).markPublished(any());
    }

    @Test
    void marksPublishedAfterRabbitMqAck() {
        DeliveryOutboxApi outboxApi = mock(DeliveryOutboxApi.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        OutboxMessage event = new OutboxMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "parcel.status.changed",
                "{\"status\":\"IN_TRANSIT\"}",
                Instant.now()
        );

        when(outboxApi.fetchUnpublished(10))
                .thenReturn(List.of(event));

        doAnswer(invocation -> {


            CorrelationData correlation = invocation.getArgument(3);


            boolean completed = correlation.getFuture().complete(
                    new CorrelationData.Confirm(true, null)
            );


            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );

        OutboxRelay relay = new OutboxRelay(outboxApi, rabbitTemplate);

        relay.relayBatch();

        verify(rabbitTemplate).send(
                eq("parcel.events"),
                eq("parcel.status.changed"),
                any(Message.class),
                any(CorrelationData.class)
        );

        verify(outboxApi).markPublished(event.eventId());
    }
}
