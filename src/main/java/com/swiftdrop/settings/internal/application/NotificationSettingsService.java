
package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.NotificationSettings;
import com.swiftdrop.settings.internal.dto.NotificationSettingsRequest;
import com.swiftdrop.settings.internal.persistence.NotificationSettingsRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class NotificationSettingsService {

    private static final Long SETTINGS_ID = 1L;

    private final NotificationSettingsRepository repository;

    public NotificationSettingsService(
            NotificationSettingsRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<NotificationSettingsRequest> getNotificationSettings() {
        return repository.findById(SETTINGS_ID)
                .map(this::toResponse);
    }

    @Transactional
    public NotificationSettingsRequest updateNotificationSettings(
            NotificationSettingsRequest request
    ) {
        String senderName = request.senderName().trim();

        if (senderName.isEmpty() || senderName.length() > 100) {
            throw new IllegalArgumentException(
                    "Sender name must contain 1 to 100 characters."
            );
        }

        NotificationSettings settings = new NotificationSettings(
                SETTINGS_ID,
                request.parcelStatusEmails(),
                request.merchantDecisionEmails(),
                senderName
        );

        return toResponse(repository.save(settings));
    }

    private NotificationSettingsRequest toResponse(
            NotificationSettings settings
    ) {
        return new NotificationSettingsRequest(
                settings.getParcelStatusEmails(),
                settings.getMerchantDecisionEmails(),
                settings.getSenderName()
        );
    }
}
