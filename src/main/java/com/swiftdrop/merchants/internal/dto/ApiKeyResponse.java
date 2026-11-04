package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.merchants.internal.domain.MerchantApiKey;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String name,
        Instant createdAt,
        Instant revokedAt
) {
    public static ApiKeyResponse from(MerchantApiKey key) {
        return new ApiKeyResponse(key.getId(), key.getName(), key.getCreatedAt(), key.getRevokedAt());
    }
}
