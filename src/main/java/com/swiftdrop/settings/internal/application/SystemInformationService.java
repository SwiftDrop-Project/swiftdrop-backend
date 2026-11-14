
package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.dto.SystemInformationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SystemInformationService {

    private final Environment environment;
    private final HealthContributorRegistry healthRegistry;

    @Value("${spring.application.version:0.0.1-SNAPSHOT}")
    private String applicationVersion;

    @Value("${swiftdrop.outbox.relay.enabled:false}")
    private boolean outboxRelayEnabled;

    public SystemInformationService(
            Environment environment,
            HealthContributorRegistry healthRegistry
    ) {
        this.environment = environment;
        this.healthRegistry = healthRegistry;
    }

    public SystemInformationResponse getSystemInformation() {

        String[] profiles = environment.getActiveProfiles();

        String activeEnvironment = profiles.length > 0
                ? String.join(", ", profiles)
                : "default";

        return new SystemInformationResponse(
                applicationVersion,
                activeEnvironment,
                getHealthStatus("db"),
                getHealthStatus("rabbit"),
                outboxRelayEnabled ? "Enabled" : "Disabled"
        );
    }

    private String getHealthStatus(String contributorName) {
        try {
            return healthRegistry.stream()
                    .filter(entry -> contributorName.equals(entry.name()))
                    .findFirst()
                    .map(entry -> {
                        if (entry.contributor() instanceof HealthIndicator indicator) {
                            Health health = indicator.health();

                            return "UP".equalsIgnoreCase(
                                    health.getStatus().getCode()
                            ) ? "Connected" : "Disconnected";
                        }

                        return "Not available";
                    })
                    .orElse("Not available");

        } catch (Exception exception) {
            return "Disconnected";
        }
    }
}
