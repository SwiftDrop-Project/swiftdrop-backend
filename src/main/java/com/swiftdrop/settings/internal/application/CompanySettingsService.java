
package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.CompanySettings;
import com.swiftdrop.settings.internal.dto.CompanyDetailsRequest;
import com.swiftdrop.settings.internal.persistence.CompanySettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class CompanySettingsService {

    private static final Long SETTINGS_ID = 1L;

    private final CompanySettingsRepository repository;

    public CompanySettingsService(CompanySettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<CompanyDetailsRequest> getCompanyDetails() {
        return repository.findById(SETTINGS_ID)
                .map(this::toResponse);
    }

    @Transactional
    public CompanyDetailsRequest updateCompanyDetails(
            CompanyDetailsRequest request) {

        CompanySettings settings = new CompanySettings(
                SETTINGS_ID,
                request.companyName().trim(),
                request.supportEmail().trim(),
                request.supportPhone().trim(),
                request.headOfficeAddress().trim(),
                request.registrationNumber() == null
                        ? null
                        : request.registrationNumber().trim()
        );

        CompanySettings saved = repository.save(settings);
        return toResponse(saved);
    }

    private CompanyDetailsRequest toResponse(CompanySettings settings) {
        return new CompanyDetailsRequest(
                settings.getCompanyName(),
                settings.getSupportEmail(),
                settings.getSupportPhone(),
                settings.getHeadOfficeAddress(),
                settings.getRegistrationNumber()
        );
    }
}
