package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.internal.application.VerificationCodeService.CheckResult;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.notifications.AccountEmail;
import com.swiftdrop.notifications.NotificationsApi;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forgot / reset / change password. Callers hash the new password with BCrypt before calling, so the slow hash
 * never holds a database connection. Every password change ends all existing sessions (token version + 1) and sends
 * a "password changed" email. Never tells the caller whether an account exists.
 */
@Service
@Transactional
public class PasswordService {

    private final UserRepository users;
    private final VerificationCodeService codes;
    private final NotificationsApi notifications;
    private final SessionStateCache sessions;

    public PasswordService(UserRepository users, VerificationCodeService codes, NotificationsApi notifications,
                           SessionStateCache sessions) {
        this.users = users;
        this.codes = codes;
        this.notifications = notifications;
        this.sessions = sessions;
    }

    /**
     * Emails a reset code to the account matching the email or mobile number. Silently does nothing for unknown
     * identifiers, during the 60-second cooldown and after 5 codes in an hour.
     */
    public void forgot(String identifier) {
        findUser(identifier).ifPresent(user ->
                codes.issue(user.getId(), VerificationPurpose.PASSWORD_RESET).ifPresent(code ->
                        notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.PASSWORD_RESET,
                                user.getEmail(), user.getFullName(), EmailVerificationService.codeVariables(code)))));
    }

    /**
     * Sets a new password if the code is right. Also works for Google-only accounts (their first password) and
     * verifies an account still waiting for email verification (the code proves the email). Wrong codes are
     * counted and committed; the caller turns a non-MATCH result into an error.
     */
    public CheckResult reset(String identifier, String code, String newPasswordHash) {
        Optional<User> found = findUser(identifier);
        if (found.isEmpty()) {
            return CheckResult.NONE;
        }
        UUID userId = found.get().getId();
        CheckResult result = codes.check(userId, VerificationPurpose.PASSWORD_RESET, code);
        if (result != CheckResult.MATCH) {
            return result;
        }
        User user = users.findByIdForUpdate(userId).orElseThrow();
        user.changePassword(newPasswordHash);
        if (user.markEmailVerified()) {
            codes.invalidateAll(userId, VerificationPurpose.EMAIL_VERIFICATION);
        }
        users.saveAndFlush(user);
        passwordChanged(user);
        return CheckResult.MATCH;
    }

    /** Sets a new password for a logged-in user (current password already checked). Returns the updated account. */
    public LoginAccount change(UUID userId, String newPasswordHash) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        user.changePassword(newPasswordHash);
        users.saveAndFlush(user);
        passwordChanged(user);
        return UserService.toLoginAccount(user);
    }

    private void passwordChanged(User user) {
        sessions.evict(user.getId());
        notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.PASSWORD_CHANGED, user.getEmail(),
                user.getFullName(), Map.of()));
    }

    /** An identifier containing {@code @} is an email (any case), anything else a mobile number in any format. */
    private Optional<User> findUser(String identifier) {
        String value = identifier.trim();
        if (value.contains("@")) {
            return users.findByEmail(UserService.normalizeEmail(value));
        }
        return PhoneNumberNormalizer.normalize(value).flatMap(users::findByPhone);
    }
}
