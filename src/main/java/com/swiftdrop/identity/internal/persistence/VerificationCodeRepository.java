package com.swiftdrop.identity.internal.persistence;

import com.swiftdrop.identity.internal.domain.VerificationCode;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    /** The live code (row-locked, so parallel guesses are counted one after the other). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VerificationCode> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID userId, VerificationPurpose purpose);

    /** The newest code of this purpose, used or not (for the resend cooldown). */
    Optional<VerificationCode> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, VerificationPurpose purpose);

    /** Codes of any purpose created after {@code since} (for the hourly cap). */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    /** Makes every live code of this purpose unusable (a new code replaces them, or the account was verified). */
    @Modifying(flushAutomatically = true)
    @Query("update VerificationCode c set c.consumedAt = :now"
            + " where c.userId = :userId and c.purpose = :purpose and c.consumedAt is null")
    int consumeAll(UUID userId, VerificationPurpose purpose, Instant now);
}
