
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.provider.ParcelEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "swiftdrop.notifications.email-worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ParcelEmailWorker {

    private static final Logger log =
            LoggerFactory.getLogger(ParcelEmailWorker.class);

    private final ParcelEmailDeliveryService deliveryService;
    private final ParcelEmailSender emailSender;

    public ParcelEmailWorker(
            ParcelEmailDeliveryService deliveryService,
            ParcelEmailSender emailSender) {
        this.deliveryService = deliveryService;
        this.emailSender = emailSender;
    }

    @Scheduled(fixedDelay = 30000)
    public void deliverPendingEmails() {

        for (UUID id : deliveryService.findDueEmails()) {
            try {
                if (!deliveryService.claim(id)) {
                    continue;
                }

                ParcelNotification notification =
                        deliveryService.findById(id).orElseThrow();

                emailSender.sendStatusEmail(
                        notification.getRecipientId(),
                        notification.getTrackingNumber(),
                        notification.getParcelStatus()
                );

                deliveryService.markSent(id);

            } catch (Exception ex) {
                log.warn(
                        "Parcel email delivery failed for notification {}: {}",
                        id,
                        ex.getClass().getSimpleName()
                );

                try {
                    deliveryService.markFailed(id, ex);
                } catch (Exception updateEx) {
                    log.error(
                            "Could not record email failure for notification {}",
                            id
                    );
                }
            }
        }
    }
}
