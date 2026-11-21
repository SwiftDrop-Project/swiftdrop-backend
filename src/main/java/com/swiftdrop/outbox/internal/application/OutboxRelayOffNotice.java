package com.swiftdrop.outbox.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One line at startup while the relay is off (the default). Without the relay, parcel events never reach RabbitMQ:
 * webhooks send only test pings, live tracking and the dispatch pickup queue don't update, and nothing shows an
 * error. The line makes that visible in the console.
 */
@Component
@ConditionalOnProperty(prefix = "swiftdrop.outbox.relay", name = "enabled", havingValue = "false",
        matchIfMissing = true)
class OutboxRelayOffNotice {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayOffNotice.class);

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        log.info("Outbox relay is off: parcel events are not published (parcel webhooks, live tracking and the "
                + "pickup queue won't update). Set SWIFTDROP_OUTBOX_RELAY_ENABLED=true in ONE backend on the shared "
                + "database.");
    }
}
