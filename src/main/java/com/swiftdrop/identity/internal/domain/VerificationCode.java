package com.swiftdrop.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-time 6-digit code sent by email. Only its HMAC is stored. Usable until it expires, is used, is replaced by a
 * newer code, or has had {@link #MAX_ATTEMPTS} wrong guesses.
 */
@Entity
@Table(name = "verification_codes", schema = "identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationCode {

    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VerificationPurpose purpose;

    /** Hex HMAC-SHA256 of user id, purpose and code. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VerificationCode(UUID userId, VerificationPurpose purpose, String codeHash, Instant createdAt,
                            Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean hasNoAttemptsLeft() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** Counts a wrong guess; returns true when that was the last allowed one. */
    public boolean recordWrongAttempt() {
        attempts++;
        return hasNoAttemptsLeft();
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    /** Keeps the hash out of logs. */
    @Override
    public String toString() {
        return "VerificationCode[id=" + id + ", purpose=" + purpose + "]";
    }
}
