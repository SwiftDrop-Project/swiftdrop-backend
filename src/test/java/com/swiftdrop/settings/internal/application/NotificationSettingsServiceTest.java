package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.NotificationSettings;
import com.swiftdrop.settings.internal.dto.NotificationSettingsRequest;
import com.swiftdrop.settings.internal.persistence.NotificationSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the admin notification settings (single row, id 1). Mocks only: no database. */
class NotificationSettingsServiceTest {

    private NotificationSettingsRepository repository;
    private NotificationSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationSettingsRepository.class);
        // save() returns what it was given, like JPA does for an assigned id.
        when(repository.save(any(NotificationSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new NotificationSettingsService(repository);
    }

    @Test
    void getReturnsEmptyWhenNothingSavedYet() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.getNotificationSettings()).isEmpty();
    }

    @Test
    void getReturnsStoredSettings() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(new NotificationSettings(1L, true, false, "SwiftDrop")));

        assertThat(service.getNotificationSettings())
                .contains(new NotificationSettingsRequest(true, false, "SwiftDrop"));
    }

    @Test
    void updateSavesSingletonRowWithBothToggles() {
        NotificationSettingsRequest result =
                service.updateNotificationSettings(new NotificationSettingsRequest(false, true, "SwiftDrop Team"));

        ArgumentCaptor<NotificationSettings> saved = ArgumentCaptor.forClass(NotificationSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(1L);
        assertThat(saved.getValue().getParcelStatusEmails()).isFalse();
        assertThat(saved.getValue().getMerchantDecisionEmails()).isTrue();
        assertThat(saved.getValue().getSenderName()).isEqualTo("SwiftDrop Team");
        assertThat(result).isEqualTo(new NotificationSettingsRequest(false, true, "SwiftDrop Team"));
    }

    @Test
    void updateTrimsSenderName() {
        NotificationSettingsRequest result =
                service.updateNotificationSettings(new NotificationSettingsRequest(true, true, "  SwiftDrop  "));

        assertThat(result.senderName()).isEqualTo("SwiftDrop");
    }

    @Test
    void savedValuesAreReturnedByTheNextGet() {
        // Persistence round trip through the repository contract: what update saves, get reads back.
        ArgumentCaptor<NotificationSettings> saved = ArgumentCaptor.forClass(NotificationSettings.class);
        service.updateNotificationSettings(new NotificationSettingsRequest(false, false, "Ops"));
        verify(repository).save(saved.capture());
        when(repository.findById(1L)).thenReturn(Optional.of(saved.getValue()));

        assertThat(service.getNotificationSettings())
                .contains(new NotificationSettingsRequest(false, false, "Ops"));
    }

    @Test
    void blankSenderNameIsRejectedWithoutSaving() {
        assertThatThrownBy(() ->
                service.updateNotificationSettings(new NotificationSettingsRequest(true, true, "   ")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void senderNameLongerThan100IsRejectedWithoutSaving() {
        assertThatThrownBy(() ->
                service.updateNotificationSettings(new NotificationSettingsRequest(true, true, "x".repeat(101))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void senderNameOfExactly100IsAccepted() {
        NotificationSettingsRequest result =
                service.updateNotificationSettings(new NotificationSettingsRequest(true, true, "x".repeat(100)));

        assertThat(result.senderName()).hasSize(100);
    }
}
