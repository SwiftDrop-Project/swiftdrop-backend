
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.persistence.ParcelNotificationRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ParcelEmailDeliveryService {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration STUCK_TIMEOUT = Duration.ofMinutes(10);

    private final ParcelNotificationRepository repository;

    public ParcelEmailDeliveryService(
            ParcelNotificationRepository repository) {
        this.repository = repository;
    }

    // Find emails ready to send
    @Transactional(readOnly = true)
    public List<UUID> findDueEmails() {
        return repository.findDueEmailIds(
                Instant.now(), MAX_ATTEMPTS, 10);
    }

    // Atomically claim an email
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID id) {
        return repository.claimEmail(
                id, Instant.now(), MAX_ATTEMPTS) == 1;
    }

    // Find notification details
    @Transactional(readOnly = true)
    public Optional<ParcelNotification> findById(UUID id) {
        return repository.findById(id);
    }

    // Mark successfully sent email
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID id) {
        int updated = repository.markEmailSent(
                id, Instant.now());

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not mark parcel email as sent");
        }
    }

    // Record email failure and schedule retry
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, Exception error) {

        String errorClass = error.getClass().getSimpleName();
        Instant nextAttempt = Instant.now().plusSeconds(60);

        int updated = repository.markEmailFailed(
                id, errorClass, nextAttempt);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not mark parcel email as failed");
        }
    }

    // Detect emails stuck in SENDING for more than 10 minutes
    // Read-only: never resends automatically
    @Transactional(readOnly = true)
    public List<UUID> findStuckEmails() {
        Instant cutoff = Instant.now().minus(STUCK_TIMEOUT);
        return repository.findStuckSendingEmails(cutoff);
    }
}
