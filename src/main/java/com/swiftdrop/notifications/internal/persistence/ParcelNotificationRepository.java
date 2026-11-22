
package com.swiftdrop.notifications.internal.persistence;

import com.swiftdrop.notifications.internal.domain.ParcelNotification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ParcelNotificationRepository
        extends JpaRepository<ParcelNotification, UUID> {

    // Check duplicate events
    boolean existsByEventId(UUID eventId);

    // Find emails ready for delivery
    @Query(value = """
            SELECT id
            FROM notifications.parcel_notification
            WHERE email_status IN ('PENDING', 'FAILED')
              AND email_attempts < :maxAttempts
              AND email_next_attempt_at <= :now
            ORDER BY email_next_attempt_at, created_at
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findDueEmailIds(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );

    // Atomically claim an email for sending
    @Modifying
    @Query(value = """
            UPDATE notifications.parcel_notification
            SET email_status = 'SENDING',
                email_attempts = email_attempts + 1,
                email_last_error = NULL,
                email_next_attempt_at = :now
            WHERE id = :id
              AND email_status IN ('PENDING', 'FAILED')
              AND email_attempts < :maxAttempts
              AND email_next_attempt_at <= :now
            """, nativeQuery = true)
    int claimEmail(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts
    );

    // Mark email successfully sent
    @Modifying
    @Query(value = """
            UPDATE notifications.parcel_notification
            SET email_status = 'SENT',
                email_sent_at = :sentAt,
                email_last_error = NULL
            WHERE id = :id
              AND email_status = 'SENDING'
            """, nativeQuery = true)
    int markEmailSent(
            @Param("id") UUID id,
            @Param("sentAt") Instant sentAt
    );

    // Record email failure and next retry time
    @Modifying
    @Query(value = """
            UPDATE notifications.parcel_notification
            SET email_status = 'FAILED',
                email_last_error = :errorClass,
                email_next_attempt_at = :nextAttemptAt
            WHERE id = :id
              AND email_status = 'SENDING'
            """, nativeQuery = true)
    int markEmailFailed(
            @Param("id") UUID id,
            @Param("errorClass") String errorClass,
            @Param("nextAttemptAt") Instant nextAttemptAt
    );

    // Detect emails stuck in SENDING state
    // Read-only: does not automatically resend emails
    @Query(value = """
            SELECT id
            FROM notifications.parcel_notification
            WHERE email_status = 'SENDING'
              AND email_next_attempt_at < :cutoff
            ORDER BY email_next_attempt_at
            """, nativeQuery = true)
    List<UUID> findStuckSendingEmails(
            @Param("cutoff") Instant cutoff
    );
}
