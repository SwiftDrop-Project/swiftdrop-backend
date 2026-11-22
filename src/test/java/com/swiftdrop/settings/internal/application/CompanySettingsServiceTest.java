package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.CompanySettings;
import com.swiftdrop.settings.internal.dto.CompanyDetailsRequest;
import com.swiftdrop.settings.internal.persistence.CompanySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the admin company details (single row, id 1). Mocks only: no database. */
class CompanySettingsServiceTest {

    private CompanySettingsRepository repository;
    private CompanySettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(CompanySettingsRepository.class);
        when(repository.save(any(CompanySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new CompanySettingsService(repository);
    }

    @Test
    void getReturnsEmptyWhenNothingSavedYet() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.getCompanyDetails()).isEmpty();
    }

    @Test
    void getReturnsStoredDetails() {
        when(repository.findById(1L)).thenReturn(Optional.of(new CompanySettings(
                1L, "SwiftDrop", "support@swiftdrop.lk", "0771234567", "Colombo 03", "PV-1234")));

        assertThat(service.getCompanyDetails()).contains(new CompanyDetailsRequest(
                "SwiftDrop", "support@swiftdrop.lk", "0771234567", "Colombo 03", "PV-1234"));
    }

    @Test
    void updateTrimsEveryFieldAndSavesRowOne() {
        CompanyDetailsRequest result = service.updateCompanyDetails(new CompanyDetailsRequest(
                "  SwiftDrop  ", " support@swiftdrop.lk ", " 0771234567 ", "  Colombo 03 ", "  PV-1234 "));

        ArgumentCaptor<CompanySettings> saved = ArgumentCaptor.forClass(CompanySettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(1L);
        assertThat(result).isEqualTo(new CompanyDetailsRequest(
                "SwiftDrop", "support@swiftdrop.lk", "0771234567", "Colombo 03", "PV-1234"));
    }

    @Test
    void registrationNumberIsOptional() {
        CompanyDetailsRequest result = service.updateCompanyDetails(new CompanyDetailsRequest(
                "SwiftDrop", "support@swiftdrop.lk", "0771234567", "Colombo 03", null));

        assertThat(result.registrationNumber()).isNull();
    }
}
