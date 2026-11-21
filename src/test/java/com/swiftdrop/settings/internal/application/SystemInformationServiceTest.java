package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.dto.SystemInformationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the System Information card. Health indicators are fakes: nothing real is contacted. */
class SystemInformationServiceTest {

    private MockEnvironment environment;
    private HealthContributorRegistry registry;
    private SystemInformationService service;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        registry = mock(HealthContributorRegistry.class);
        service = new SystemInformationService(environment, registry);
        ReflectionTestUtils.setField(service, "applicationVersion", "1.2.3");
        ReflectionTestUtils.setField(service, "outboxRelayEnabled", false);
    }

    private void health(HealthContributors.Entry... entries) {
        when(registry.stream()).thenAnswer(inv -> Stream.of(entries));
    }

    private static HealthContributors.Entry entry(String name, Health health) {
        return new HealthContributors.Entry(name, (HealthIndicator) () -> health);
    }

    @Test
    void reportsVersionDefaultEnvironmentAndConnectedServices() {
        health(entry("db", Health.up().build()), entry("rabbit", Health.up().build()));

        assertThat(service.getSystemInformation()).isEqualTo(
                new SystemInformationResponse("1.2.3", "default", "Connected", "Connected", "Disabled"));
    }

    @Test
    void reportsActiveProfilesAndEnabledRelay() {
        environment.setActiveProfiles("demo", "local");
        ReflectionTestUtils.setField(service, "outboxRelayEnabled", true);
        health();

        SystemInformationResponse info = service.getSystemInformation();

        assertThat(info.environment()).isEqualTo("demo, local");
        assertThat(info.outboxRelayStatus()).isEqualTo("Enabled");
    }

    @Test
    void downServiceIsDisconnectedAndMissingOneIsNotAvailable() {
        health(entry("db", Health.down().build()));

        SystemInformationResponse info = service.getSystemInformation();

        assertThat(info.databaseStatus()).isEqualTo("Disconnected");
        assertThat(info.rabbitMqStatus()).isEqualTo("Not available");
    }

    @Test
    void failingHealthCheckIsReportedAsDisconnected() {
        when(registry.stream()).thenThrow(new IllegalStateException("registry failure"));

        SystemInformationResponse info = service.getSystemInformation();

        assertThat(info.databaseStatus()).isEqualTo("Disconnected");
        assertThat(info.rabbitMqStatus()).isEqualTo("Disconnected");
    }
}
