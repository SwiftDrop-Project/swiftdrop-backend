
package com.swiftdrop.settings.internal.dto;

public record SystemInformationResponse(
        String applicationVersion,
        String environment,
        String databaseStatus,
        String rabbitMqStatus,
        String outboxRelayStatus
) {
}
