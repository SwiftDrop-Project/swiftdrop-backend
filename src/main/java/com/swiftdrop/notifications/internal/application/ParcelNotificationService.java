
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.persistence.ParcelNotificationRepository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ParcelNotificationService {

    private final EntityManager entityManager;
    private final ParcelNotificationRepository notifications;

    public ParcelNotificationService(
            EntityManager entityManager,
            ParcelNotificationRepository notifications) {

        this.entityManager = entityManager;
        this.notifications = notifications;
    }

    @Transactional
    public void process(ParcelStatusChanged event) {

        // Step 1: Validate incoming event
        if (event == null ||
                event.eventId() == null ||
                event.parcelId() == null ||
                event.toStatus() == null ||
                event.customerId() == null ||
                event.parcelVersion() < 0) {

            throw new IllegalArgumentException(
                    "Invalid parcel notification event"
            );
        }

        // Step 2: Atomically track the latest parcel version
        int advanced = entityManager.createNativeQuery("""
                INSERT INTO notifications.parcel_event_progress
                    (parcel_id, last_processed_version, updated_at)
                VALUES (:parcelId, :version, NOW())
                ON CONFLICT (parcel_id)
                DO UPDATE SET
                    last_processed_version = EXCLUDED.last_processed_version,
                    updated_at = NOW()
                WHERE notifications.parcel_event_progress.last_processed_version
                    < EXCLUDED.last_processed_version
                """)
                .setParameter("parcelId", event.parcelId())
                .setParameter("version", event.parcelVersion())
                .executeUpdate();

        // Step 3: Skip duplicate or older parcel events
        if (advanced == 0) {
            return;
        }

        // Step 4: Identify notification recipient
        UUID recipient = event.customerId();

        // Step 5: Generate notification message
        String message = "Parcel " + event.trackingNumber()
                + " status changed to " + event.toStatus();

        // Step 6: Create notification record
        ParcelNotification notification = new ParcelNotification(
                event.eventId(),
                event.parcelId(),
                event.parcelVersion(),
                recipient,
                event.trackingNumber(),
                event.toStatus().name(),
                message
        );

        // Step 7: Save notification
        notifications.saveAndFlush(notification);
    }
}
