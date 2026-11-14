package com.swiftdrop.infrastructure;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** A parcel.status.changed message on the parcel.events exchange reaches both durable queues. */
class RabbitTopologyTest extends IntegrationTest {

    /** The webhooks module's consumer of webhooks.q (WebhookEventListener). */
    private static final String WEBHOOKS_LISTENER_ID = "webhooks-parcel-events";

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitListenerEndpointRegistry listeners;

    @Test
    void parcelStatusChangedReachesNotificationsAndWebhooks() {
        rabbitAdmin.initialize();
        assertThat(rabbitAdmin.getQueueProperties("notifications.q")).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties("webhooks.q")).isNotNull();

        // webhooks.q has a consumer: pause it, or it takes the test message before this test can read it
        var webhooksConsumer = listeners.getListenerContainer(WEBHOOKS_LISTENER_ID);
        assertThat(webhooksConsumer).as("webhooks.q listener").isNotNull();
        webhooksConsumer.stop();
        try {
            rabbitTemplate.convertAndSend("parcel.events", "parcel.status.changed", "topology-test");

            for (String queue : new String[] {"notifications.q", "webhooks.q"}) {
                Message received = rabbitTemplate.receive(queue, 10_000);
                assertThat(received).as(queue).isNotNull();
                assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo("topology-test");
            }
        } finally {
            webhooksConsumer.start(); // the Spring context is shared with the other tests
        }
    }
}
