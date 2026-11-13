
package com.swiftdrop.notifications.internal.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.infrastructure.internal.messaging.RabbitTopology;
import com.swiftdrop.notifications.internal.application.ParcelNotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "swiftdrop.notifications.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ParcelNotificationConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(ParcelNotificationConsumer.class);

    private final ObjectMapper objectMapper;
    private final ParcelNotificationService notificationService;

    public ParcelNotificationConsumer(
            ObjectMapper objectMapper,
            ParcelNotificationService notificationService) {

        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
    }

    @RabbitListener(
            queues = RabbitTopology.NOTIFICATIONS_QUEUE,
            containerFactory = "parcelNotificationListenerFactory"
    )
    public void consume(Message message) throws Exception {

        try {
            ParcelStatusChanged event = objectMapper.readValue(
                    message.getBody(),
                    ParcelStatusChanged.class
            );

            notificationService.process(event);

        } catch (Exception ex) {

            log.error(
                    "Parcel notification processing failed: {}",
                    ex.getClass().getSimpleName()
            );

            throw ex;
        }
    }
}
