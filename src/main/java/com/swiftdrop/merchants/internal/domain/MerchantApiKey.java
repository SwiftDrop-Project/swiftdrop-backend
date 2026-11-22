package com.swiftdrop.merchants.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * An API key belonging to a merchant. The raw key is never stored — only its SHA-256 hash.
 * merchant_user_id refers to identity.users by UUID; no foreign key (project rule).
 */
@Entity
@Table(name = "api_keys", schema = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantApiKey implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "merchant_user_id", nullable = false)
    private UUID merchantUserId;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Transient
    private boolean isNew = true;

    public MerchantApiKey(UUID id, UUID merchantUserId, String keyHash, String name, Instant createdAt) {
        this.id = id;
        this.merchantUserId = merchantUserId;
        this.keyHash = keyHash;
        this.name = name;
        this.createdAt = createdAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
