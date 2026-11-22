
package com.swiftdrop.notifications.internal.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.notifications.internal.application.ParcelNotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParcelNotificationConsumerTest {

    @Test
    void validEventIsForwardedToService() throws Exception {

        ObjectMapper mapper = mock(ObjectMapper.class);
        ParcelNotificationService service =
                mock(ParcelNotificationService.class);

        ParcelStatusChanged event =
                mock(ParcelStatusChanged.class);

        byte[] payload =
                "{}".getBytes(StandardCharsets.UTF_8);

        Message message = new Message(
                payload,
                new MessageProperties()
        );

        when(mapper.readValue(payload, ParcelStatusChanged.class))
                .thenReturn(event);

        ParcelNotificationConsumer consumer =
                new ParcelNotificationConsumer(mapper, service);

        consumer.consume(message);

        verify(service).process(event);
    }

    @Test
    void processingFailurePropagates() throws Exception {

        ObjectMapper mapper = mock(ObjectMapper.class);
        ParcelNotificationService service =
                mock(ParcelNotificationService.class);

        ParcelStatusChanged event =
                mock(ParcelStatusChanged.class);

        byte[] payload =
                "{}".getBytes(StandardCharsets.UTF_8);

        Message message = new Message(
                payload,
                new MessageProperties()
        );

        when(mapper.readValue(payload, ParcelStatusChanged.class))
                .thenReturn(event);

        doThrow(new IllegalStateException("Processing failed"))
                .when(service).process(any());

        ParcelNotificationConsumer consumer =
                new ParcelNotificationConsumer(mapper, service);

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(message)
        );

        verify(service).process(event);
    }
}
