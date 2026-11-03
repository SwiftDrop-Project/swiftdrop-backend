package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.internal.domain.VerificationCode;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.persistence.VerificationCodeRepository;
import com.swiftdrop.identity.internal.security.CodeHasher;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time 6-digit email codes: issuing (with a resend cooldown and an hourly cap) and checking (with a limit on
 * wrong guesses). Codes are stored only as an HMAC ({@link CodeHasher}) and are never logged.
 *
 * <p>Every method joins the caller's transaction ({@code MANDATORY}): the caller decides what happens together
 * with the code, e.g. a failed check must still commit the counted attempt, so callers return the result instead of
 * throwing inside the transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class VerificationCodeService {

    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    public static final int MAX_CODES_PER_HOUR = 5;

    /** Outcome of {@link #check}. */
    public enum CheckResult {
        /** Right code; it is now used up. */
        MATCH,
        /** Wrong code, attempts left. */
        INVALID,
        /** The live code has expired. */
        EXPIRED,
        /** Five wrong guesses: the code is dead. */
        TOO_MANY_ATTEMPTS,
        /** No live code (never sent, already used or replaced). */
        NONE;

        /** The API error for anything but MATCH. */
        public ApiException toException() {
            return switch (this) {
                case MATCH -> throw new IllegalStateException("MATCH is not an error");
                case INVALID, NONE -> new ApiException(ErrorCode.INVALID_CODE);
                case EXPIRED -> new ApiException(ErrorCode.CODE_EXPIRED);
                case TOO_MANY_ATTEMPTS -> new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
            };
        }
    }

    private final VerificationCodeRepository codes;
    private final UserRepository users;
    private final CodeHasher hasher;

    public VerificationCodeService(VerificationCodeRepository codes, UserRepository users, CodeHasher hasher) {
        this.codes = codes;
        this.users = users;
        this.hasher = hasher;
    }

    /**
     * A new code for the user, replacing the older codes of this purpose. Empty (and nothing changes) when the last
     * code of this purpose is less than 60 seconds old or the user already got 5 codes in the last hour.
     * The returned code must only be put in the email.
     */
    public Optional<String> issue(UUID userId, VerificationPurpose purpose) {
        // Locks the user row, so two parallel requests cannot both pass the limits.
        users.findByIdForUpdate(userId);
        Instant now = Instant.now();
        boolean coolingDown = codes.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, purpose)
                .map(last -> last.getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN)))
                .orElse(false);
        if (coolingDown || codes.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofHours(1)))
                >= MAX_CODES_PER_HOUR) {
            return Optional.empty();
        }
        codes.consumeAll(userId, purpose, now);
        String code = hasher.newCode();
        codes.save(new VerificationCode(userId, purpose, hasher.hash(userId, purpose, code), now,
                now.plus(CODE_LIFETIME)));
        return Optional.of(code);
    }

    /**
     * Checks {@code code} against the user's live code of this purpose. A wrong code counts as an attempt; the
     * fifth wrong attempt kills the code. A match uses the code up.
     */
    public CheckResult check(UUID userId, VerificationPurpose purpose, String code) {
        Optional<VerificationCode> found =
                codes.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(userId, purpose);
        if (found.isEmpty()) {
            return CheckResult.NONE;
        }
        VerificationCode live = found.get();
        Instant now = Instant.now();
        if (live.hasNoAttemptsLeft()) {
            return CheckResult.TOO_MANY_ATTEMPTS;
        }
        if (live.isExpired(now)) {
            return CheckResult.EXPIRED;
        }
        if (code == null || !hasher.matches(live.getCodeHash(), userId, purpose, code)) {
            return live.recordWrongAttempt() ? CheckResult.TOO_MANY_ATTEMPTS : CheckResult.INVALID;
        }
        live.consume(now);
        return CheckResult.MATCH;
    }

    /** Makes every live code of this purpose unusable, e.g. after Google proved the email. */
    public void invalidateAll(UUID userId, VerificationPurpose purpose) {
        codes.consumeAll(userId, purpose, Instant.now());
    }
}
