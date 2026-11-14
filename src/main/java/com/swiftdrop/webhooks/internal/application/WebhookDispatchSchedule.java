package com.swiftdrop.webhooks.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link WebhookDispatcher#dispatchDue()} every {@code swiftdrop.webhooks.dispatcher.interval-ms} (default 2 s),
 * only when {@code swiftdrop.webhooks.dispatcher.enabled=true} (SWIFTDROP_WEBHOOKS_DISPATCHER_ENABLED; on by default,
 * off in tests). The {@code @Scheduled} method lives in this conditional class, not in the dispatcher, because
 * {@code @EnableScheduling} is global: another module turning scheduling on (the outbox relay) must not start it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "swiftdrop.webhooks.dispatcher", name = "enabled", havingValue = "true")
class WebhookDispatchSchedule {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchSchedule.class);

    private final WebhookDispatcher dispatcher;

    WebhookDispatchSchedule(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${swiftdrop.webhooks.dispatcher.interval-ms:2000}",
            fixedDelayString = "${swiftdrop.webhooks.dispatcher.interval-ms:2000}")
    void tick() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException ex) {
            // e.g. the database is unreachable: one short line per round, not a stack trace every 2 seconds
            log.warn("Webhook dispatch skipped this round: {}", ex.getClass().getSimpleName());
        }
    }
}
