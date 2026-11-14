package com.swiftdrop.webhooks.internal.messaging;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.webhooks.internal.application.WebhookFanOutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code ParcelStatusChanged} from RabbitMQ queue {@value #QUEUE} (exchange {@code parcel.events}, routing key
 * {@code parcel.status.changed}, published by the outbox relay).
 *
 * <p>The queue and its binding are declared by infrastructure (RabbitTopology); this class must not declare them
 * again (RabbitMQ refuses a declaration with other arguments). The name is written out because another module's
 * internal constants may not be used.
 */
@Component
class WebhookEventListener {

    static final String QUEUE = "webhooks.q";
    /** Listener container id: RabbitTopologyTest pauses this consumer to read webhooks.q itself. */
    static final String LISTENER_ID = "webhooks-parcel-events";

    private static final Logger log = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookFanOutService fanOut;
    private final JsonMapper jsonMapper;

    WebhookEventListener(WebhookFanOutService fanOut, JsonMapper jsonMapper) {
        this.fanOut = fanOut;
        this.jsonMapper = jsonMapper;
    }

    /**
     * One event. A message that can't be read is logged and dropped (redelivering it would fail the same way);
     * a failure while handling it is thrown, so RabbitMQ redelivers it (handling is idempotent).
     */
    @RabbitListener(id = LISTENER_ID, queues = QUEUE)
    void onParcelStatusChanged(Message message) {
        ParcelStatusChanged event;
        try {
            event = jsonMapper.readValue(message.getBody(), ParcelStatusChanged.class);
        } catch (JacksonException e) {
            log.warn("Dropped an unreadable parcel event (message id {})",
                    message.getMessageProperties().getMessageId());
            return;
        }
        fanOut.handle(event);
    }
}
