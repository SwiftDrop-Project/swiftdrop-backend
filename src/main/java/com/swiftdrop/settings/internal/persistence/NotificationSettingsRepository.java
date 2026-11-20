
package com.swiftdrop.settings.internal.persistence;

import com.swiftdrop.settings.internal.domain.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingsRepository
        extends JpaRepository<NotificationSettings, Long> {
}
