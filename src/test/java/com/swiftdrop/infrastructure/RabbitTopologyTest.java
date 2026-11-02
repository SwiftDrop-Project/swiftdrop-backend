package com.swiftdrop.infrastructure;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** A parcel.status.changed message on the parcel.events exchange reaches both durable queues. */
class RabbitTopologyTest extends IntegrationTest {

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    void parcelStatusChangedReachesNotificationsAndWebhooks() {
        rabbitAdmin.initialize();
        assertThat(rabbitAdmin.getQueueProperties("notifications.q")).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties("webhooks.q")).isNotNull();

        rabbitTemplate.convertAndSend("parcel.events", "parcel.status.changed", "topology-test");

        for (String queue : new String[] {"notifications.q", "webhooks.q"}) {
            Message received = rabbitTemplate.receive(queue, 10_000);
            assertThat(received).as(queue).isNotNull();
            assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo("topology-test");
        }
    }
}
