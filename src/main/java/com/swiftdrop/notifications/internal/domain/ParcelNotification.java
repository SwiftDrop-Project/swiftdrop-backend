
package com.swiftdrop.notifications.internal.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parcel_notification", schema = "notifications")
public class ParcelNotification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "parcel_version", nullable = false)
    private long parcelVersion;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "parcel_status", nullable = false, length = 40)
    private String parcelStatus;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "email_status", nullable = false, length = 20)
    private String emailStatus;

    @Column(name = "email_attempts", nullable = false)
    private int emailAttempts;

    @Column(name = "email_last_error", length = 255)
    private String emailLastError;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    @Column(name = "email_next_attempt_at", nullable = false)
    private Instant emailNextAttemptAt;

    protected ParcelNotification() {
    }

    public ParcelNotification(
            UUID eventId,
            UUID parcelId,
            long parcelVersion,
            UUID recipientId,
            String trackingNumber,
            String parcelStatus,
            String message) {

        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.parcelId = parcelId;
        this.parcelVersion = parcelVersion;
        this.recipientId = recipientId;
        this.trackingNumber = trackingNumber;
        this.parcelStatus = parcelStatus;
        this.message = message;
        this.createdAt = Instant.now();

        this.emailStatus = "PENDING";
        this.emailAttempts = 0;
        this.emailNextAttemptAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getParcelStatus() {
        return parcelStatus;
    }

    public String getEmailStatus() {
        return emailStatus;
    }

    public int getEmailAttempts() {
        return emailAttempts;
    }
}
