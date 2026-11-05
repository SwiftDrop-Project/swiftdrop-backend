package com.swiftdrop.merchants.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record CreateApiKeyResponse(
        UUID id,
        String name,
        @Schema(description = "Shown only once. Store securely — it cannot be retrieved again.")
        String rawKey,
        Instant createdAt
) {
}
