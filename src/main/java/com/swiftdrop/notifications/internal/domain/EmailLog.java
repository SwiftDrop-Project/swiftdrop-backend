package com.swiftdrop.notifications.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One sent (or failed) email. Never holds the full address or the email's content. */
@Entity
@Table(name = "email_log", schema = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** e.g. EMAIL_VERIFICATION */
    @Column(nullable = false, length = 40)
    private String type;

    /** e.g. a•••@gmail.com */
    @Column(name = "recipient_masked", nullable = false, length = 254)
    private String recipientMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EmailStatus status;

    /** Exception class name when FAILED (never the message, which can contain the address). */
    @Column(name = "error_class")
    private String errorClass;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EmailLog(String type, String recipientMasked, EmailStatus status, String errorClass) {
        this.type = type;
        this.recipientMasked = recipientMasked;
        this.status = status;
        this.errorClass = errorClass;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
