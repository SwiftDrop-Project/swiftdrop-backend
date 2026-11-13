
package com.swiftdrop.notifications.internal.application;

import com.swiftdrop.notifications.internal.domain.ParcelNotification;
import com.swiftdrop.notifications.internal.provider.ParcelEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ParcelEmailWorkerTest {

    private ParcelEmailDeliveryService deliveryService;
    private ParcelEmailSender emailSender;
    private ParcelEmailWorker worker;

    @BeforeEach
    void setUp() {
        deliveryService = mock(ParcelEmailDeliveryService.class);
        emailSender = mock(ParcelEmailSender.class);
        worker = new ParcelEmailWorker(deliveryService, emailSender);
    }

    @Test
    void sendsPendingEmailAndMarksSent() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        ParcelNotification notification = new ParcelNotification(
                eventId, parcelId, 1L, customerId,
                "SWD12345", "DELIVERED", "Parcel delivered"
        );

        when(deliveryService.findDueEmails()).thenReturn(List.of(id));
        when(deliveryService.claim(id)).thenReturn(true);
        when(deliveryService.findById(id))
                .thenReturn(Optional.of(notification));

        worker.deliverPendingEmails();

        verify(emailSender).sendStatusEmail(
                customerId, "SWD12345", "DELIVERED");
        verify(deliveryService).markSent(id);
        verify(deliveryService, never()).markFailed(eq(id), any());
    }

    @Test
    void skipsAlreadyClaimedEmail() {
        UUID id = UUID.randomUUID();

        when(deliveryService.findDueEmails()).thenReturn(List.of(id));
        when(deliveryService.claim(id)).thenReturn(false);

        worker.deliverPendingEmails();

        verifyNoInteractions(emailSender);
        verify(deliveryService, never()).markSent(any());
    }

    @Test
    void recordsEmailFailure() {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        ParcelNotification notification = new ParcelNotification(
                UUID.randomUUID(), UUID.randomUUID(),
                1L, customerId, "SWD12345",
                "DELIVERED", "Parcel delivered"
        );

        when(deliveryService.findDueEmails()).thenReturn(List.of(id));
        when(deliveryService.claim(id)).thenReturn(true);
        when(deliveryService.findById(id))
                .thenReturn(Optional.of(notification));

        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(emailSender)
                .sendStatusEmail(customerId, "SWD12345", "DELIVERED");

        worker.deliverPendingEmails();

        verify(deliveryService).markFailed(
                eq(id), any(IllegalStateException.class));
        verify(deliveryService, never()).markSent(id);
    }
}
