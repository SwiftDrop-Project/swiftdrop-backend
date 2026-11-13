
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.persistence.ParcelNotificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParcelNotificationServiceTest {

    private EntityManager entityManager;
    private Query query;
    private ParcelNotificationRepository repository;
    private ParcelNotificationService service;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        repository = mock(ParcelNotificationRepository.class);

        when(entityManager.createNativeQuery(anyString()))
                .thenReturn(query);
        when(query.setParameter(anyString(), any()))
                .thenReturn(query);

        service = new ParcelNotificationService(
                entityManager, repository);
    }

    private ParcelStatusChanged event() {
        return new ParcelStatusChanged(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2L,
                "SWD12345",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                ParcelStatus.DELIVERED,
                UUID.randomUUID(),
                "COURIER",
                null,
                null,
                Instant.now(),
                null
        );
    }

    @Test
    void invalidEventIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.process(null)
        );

        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void duplicateOrOlderVersionIsSkipped() {
        when(query.executeUpdate()).thenReturn(0);

        service.process(event());

        verify(repository, never())
                .saveAndFlush(any(ParcelNotification.class));
    }

    @Test
    void newEventCreatesNotification() {
        when(query.executeUpdate()).thenReturn(1);

        service.process(event());

        verify(repository, times(1))
                .saveAndFlush(any(ParcelNotification.class));
    }

    @Test
    void databaseFailurePropagates() {
        when(query.executeUpdate())
                .thenThrow(new IllegalStateException("DB failure"));

        assertThrows(
                IllegalStateException.class,
                () -> service.process(event())
        );

        verify(repository, never())
                .saveAndFlush(any(ParcelNotification.class));
    }
}
