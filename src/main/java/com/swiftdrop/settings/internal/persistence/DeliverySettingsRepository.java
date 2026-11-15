
package com.swiftdrop.settings.internal.persistence;

import com.swiftdrop.settings.internal.domain.DeliverySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliverySettingsRepository
        extends JpaRepository<DeliverySettings, Long> {
}
