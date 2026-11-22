package com.swiftdrop.merchants.internal.persistence;

import com.swiftdrop.merchants.internal.domain.MerchantApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, UUID> {

    List<MerchantApiKey> findAllByMerchantUserIdOrderByCreatedAtDesc(UUID merchantUserId);

    Optional<MerchantApiKey> findByKeyHash(String keyHash);
}
