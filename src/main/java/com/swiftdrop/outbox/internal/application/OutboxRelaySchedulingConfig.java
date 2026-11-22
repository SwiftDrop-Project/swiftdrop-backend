package com.swiftdrop.outbox.internal.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "swiftdrop.outbox.relay",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OutboxRelaySchedulingConfig {
}