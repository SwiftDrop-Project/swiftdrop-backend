
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.internal.persistence.ParcelNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParcelEmailDeliveryServiceTest {

    private ParcelNotificationRepository repository;
    private ParcelEmailDeliveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ParcelNotificationRepository.class);
        service = new ParcelEmailDeliveryService(repository);
    }

    @Test
    void findsDueEmails() {
        UUID id = UUID.randomUUID();

        when(repository.findDueEmailIds(
                any(Instant.class), eq(3), eq(10)))
                .thenReturn(List.of(id));

        List<UUID> result = service.findDueEmails();

        assertEquals(List.of(id), result);
    }

    @Test
    void claimsPendingEmail() {
        UUID id = UUID.randomUUID();

        when(repository.claimEmail(
                eq(id), any(Instant.class), eq(3)))
                .thenReturn(1);

        assertTrue(service.claim(id));
    }

    @Test
    void skipsAlreadyClaimedEmail() {
        UUID id = UUID.randomUUID();

        when(repository.claimEmail(
                eq(id), any(Instant.class), eq(3)))
                .thenReturn(0);

        assertFalse(service.claim(id));
    }

    @Test
    void marksEmailAsSent() {
        UUID id = UUID.randomUUID();

        when(repository.markEmailSent(
                eq(id), any(Instant.class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.markSent(id));
    }

    @Test
    void recordsFailedEmail() {
        UUID id = UUID.randomUUID();

        when(repository.markEmailFailed(
                eq(id),
                eq("IllegalStateException"),
                any(Instant.class)))
                .thenReturn(1);

        assertDoesNotThrow(() ->
                service.markFailed(
                        id, new IllegalStateException("SMTP error")));
    }

@Test
void findsStuckSendingEmails() {
    UUID id = UUID.randomUUID();

    when(repository.findStuckSendingEmails(
            any(Instant.class)))
            .thenReturn(List.of(id));

    List<UUID> result = service.findStuckEmails();

    assertEquals(List.of(id), result);

    verify(repository).findStuckSendingEmails(
            argThat(cutoff ->
                    cutoff.isBefore(Instant.now())
            )
    );
}

}
