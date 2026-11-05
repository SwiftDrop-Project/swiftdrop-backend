package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.merchants.internal.domain.MerchantApiKey;
import com.swiftdrop.merchants.internal.dto.ApiKeyResponse;
import com.swiftdrop.merchants.internal.dto.CreateApiKeyResponse;
import com.swiftdrop.merchants.internal.persistence.MerchantApiKeyRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ApiKeyService {

    private final MerchantApiKeyRepository repository;

    public ApiKeyService(MerchantApiKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CreateApiKeyResponse createKey(UUID merchantUserId, String name) {
        String rawKey = generateRawKey();
        MerchantApiKey key = new MerchantApiKey(UUID.randomUUID(), merchantUserId, sha256Hex(rawKey), name, Instant.now());
        repository.save(key);
        return new CreateApiKeyResponse(key.getId(), key.getName(), rawKey, key.getCreatedAt());
    }

    public List<ApiKeyResponse> listKeys(UUID merchantUserId) {
        return repository.findAllByMerchantUserIdOrderByCreatedAtDesc(merchantUserId)
                .stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @Transactional
    public ApiKeyResponse revokeKey(UUID merchantUserId, UUID keyId) {
        MerchantApiKey key = findOwnedKey(merchantUserId, keyId);
        if (key.isActive()) {
            key.revoke();
            repository.save(key);
        }
        return ApiKeyResponse.from(key);
    }

    @Transactional
    public CreateApiKeyResponse rotateKey(UUID merchantUserId, UUID keyId) {
        MerchantApiKey old = findOwnedKey(merchantUserId, keyId);
        if (old.isActive()) {
            old.revoke();
            repository.save(old);
        }
        return createKey(merchantUserId, old.getName());
    }

    public Optional<MerchantApiKey> findActiveByHash(String rawKey) {
        return repository.findByKeyHash(sha256Hex(rawKey))
                .filter(MerchantApiKey::isActive);
    }

    private MerchantApiKey findOwnedKey(UUID merchantUserId, UUID keyId) {
        MerchantApiKey key = repository.findById(keyId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (!key.getMerchantUserId().equals(merchantUserId)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN);
        }
        return key;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String generateRawKey() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return "sd_" + HexFormat.of().formatHex(bytes);
    }
}
