
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.persistence.ParcelNotificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    private ParcelStatusChanged event(
            UUID eventId,
            UUID parcelId,
            long parcelVersion,
            UUID customerId,
            UUID merchantId,
            ParcelStatus toStatus) {

        return new ParcelStatusChanged(
                eventId,
                parcelId,
                parcelVersion,
                "SD2611004817",
                customerId,
                merchantId,
                ParcelStatus.CREATED,
                toStatus,
                UUID.randomUUID(),
                "COURIER",
                null,
                null,
                Instant.now(),
                null,
                "ORDER-1001"
        );
    }

    /** A merchant API order: no customer account, only a merchant. */
    private ParcelStatusChanged merchantEvent() {
        return event(UUID.randomUUID(), UUID.randomUUID(), 3L,
                null, UUID.randomUUID(), ParcelStatus.PICKED_UP);
    }

    @Test
    void merchantOrderEventIsSkippedWithoutException() {
        assertDoesNotThrow(() -> service.process(merchantEvent()));
    }

    @Test
    void merchantOrderEventDoesNoDatabaseWork() {
        service.process(merchantEvent());

        // No parcel_event_progress upsert and no parcel_notification row.
        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void eventWithMissingRequiredFieldIsRejected() {
        UUID customer = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> service.process(
                event(null, UUID.randomUUID(), 1L, customer, null, ParcelStatus.DELIVERED)));
        assertThrows(IllegalArgumentException.class, () -> service.process(
                event(UUID.randomUUID(), null, 1L, customer, null, ParcelStatus.DELIVERED)));
        assertThrows(IllegalArgumentException.class, () -> service.process(
                event(UUID.randomUUID(), UUID.randomUUID(), 1L, customer, null, null)));

        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void eventWithNegativeVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.process(
                event(UUID.randomUUID(), UUID.randomUUID(), -1L,
                        UUID.randomUUID(), null, ParcelStatus.DELIVERED)));

        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void invalidMerchantEventIsStillRejected() {
        // Validation runs before the merchant skip, so a broken merchant
        // event is not silently swallowed.
        assertThrows(IllegalArgumentException.class, () -> service.process(
                event(null, UUID.randomUUID(), 1L,
                        null, UUID.randomUUID(), ParcelStatus.DELIVERED)));

        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void customerEventCreatesNotificationForThatCustomer() {
        when(query.executeUpdate()).thenReturn(1);
        UUID customer = UUID.randomUUID();
        UUID parcel = UUID.randomUUID();

        service.process(event(UUID.randomUUID(), parcel, 4L,
                customer, null, ParcelStatus.OUT_FOR_DELIVERY));

        verify(query).setParameter("parcelId", parcel);
        verify(query).setParameter("version", 4L);

        ArgumentCaptor<ParcelNotification> saved =
                ArgumentCaptor.forClass(ParcelNotification.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(customer, saved.getValue().getRecipientId());
        assertEquals("OUT_FOR_DELIVERY", saved.getValue().getParcelStatus());
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
