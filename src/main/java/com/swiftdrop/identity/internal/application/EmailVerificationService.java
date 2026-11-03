package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.application.VerificationCodeService.CheckResult;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.notifications.AccountEmail;
import com.swiftdrop.notifications.NotificationsApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirms the email address of password sign-ups with a 6-digit code (status PENDING_EMAIL_VERIFICATION).
 * Never tells the caller whether an email is registered: unknown addresses behave like wrong codes.
 */
@Service
@Transactional
public class EmailVerificationService {

    private final UserRepository users;
    private final VerificationCodeService codes;
    private final NotificationsApi notifications;
    private final SessionStateCache sessions;

    public EmailVerificationService(UserRepository users, VerificationCodeService codes,
                                    NotificationsApi notifications, SessionStateCache sessions) {
        this.users = users;
        this.codes = codes;
        this.notifications = notifications;
        this.sessions = sessions;
    }

    /** Result of {@link #verify}: {@code account} is set only for MATCH. */
    public record Outcome(CheckResult result, LoginAccount account) {
    }

    /**
     * Emails a code to a user who just signed up. Runs in the sign-up transaction, so the email is sent only if
     * the account is really created.
     */
    public void sendCode(UUID userId, String email, String displayName) {
        codes.issue(userId, VerificationPurpose.EMAIL_VERIFICATION).ifPresent(code ->
                notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.EMAIL_VERIFICATION, email,
                        displayName, codeVariables(code))));
    }

    /**
     * A new code for an account waiting for verification. Silently does nothing for unknown or already verified
     * emails, during the 60-second cooldown and after 5 codes in an hour.
     */
    public void resend(String email) {
        users.findByEmail(UserService.normalizeEmail(email))
                .filter(user -> user.getStatus() == UserStatus.PENDING_EMAIL_VERIFICATION)
                .ifPresent(user -> sendCode(user.getId(), user.getEmail(), user.getFullName()));
    }

    /**
     * Checks the code. On a match the account is verified: customers become ACTIVE, merchants PENDING_APPROVAL.
     * A wrong code is counted (and committed) even though the caller then answers with an error.
     */
    public Outcome verify(String email, String code) {
        Optional<User> found = users.findByEmail(UserService.normalizeEmail(email))
                .filter(user -> user.getStatus() == UserStatus.PENDING_EMAIL_VERIFICATION);
        if (found.isEmpty()) {
            return new Outcome(CheckResult.NONE, null);
        }
        UUID userId = found.get().getId();
        CheckResult result = codes.check(userId, VerificationPurpose.EMAIL_VERIFICATION, code);
        if (result != CheckResult.MATCH) {
            return new Outcome(result, null);
        }
        User user = users.findByIdForUpdate(userId).orElseThrow();
        user.markEmailVerified();
        sessions.evict(userId);
        return new Outcome(CheckResult.MATCH, UserService.toLoginAccount(users.saveAndFlush(user)));
    }

    static Map<String, String> codeVariables(String code) {
        return Map.of("code", code, "minutes", String.valueOf(VerificationCodeService.CODE_LIFETIME.toMinutes()));
    }
}
