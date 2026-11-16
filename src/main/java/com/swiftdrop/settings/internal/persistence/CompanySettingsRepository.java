
package com.swiftdrop.settings.internal.persistence;

import com.swiftdrop.settings.internal.domain.CompanySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanySettingsRepository
        extends JpaRepository<CompanySettings, Long> {
}
