package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.MerchantProfiles.Profile;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.identity.internal.application.VerificationCodeService.CheckResult;
import com.swiftdrop.identity.internal.security.TokenService;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;
import com.swiftdrop.shared.validation.PhoneNumberNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    static final String WRONG_CURRENT_PASSWORD = "Your current password is incorrect.";
    static final String NO_PASSWORD_YET = "This account has no password yet. Use Forgot password to set one.";

    private final UserService userService;
    private final MerchantProfiles merchantProfiles;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerification;
    private final PasswordService passwordService;
    /** Compared against when the user doesn't exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(UserService userService, MerchantProfiles merchantProfiles, TokenService tokenService,
                       PasswordEncoder passwordEncoder, EmailVerificationService emailVerification,
                       PasswordService passwordService) {
        this.userService = userService;
        this.merchantProfiles = merchantProfiles;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.emailVerification = emailVerification;
        this.passwordService = passwordService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    /** A user plus, for merchants, their profile (from the merchants module). */
    public record Account(UserSummary user, Profile merchant) {
    }

    /** {@code token} is null when the user must not be logged in yet (email not verified, merchant awaiting approval). */
    public record AuthResult(Account account, String token) {
    }

    /** A Google account whose email Google has verified. {@code subject} is Google's stable account id. */
    public record GoogleIdentity(String subject, String email, String fullName) {

        /** Keeps personal data out of logs. */
        @Override
        public String toString() {
            return "GoogleIdentity[]";
        }
    }

    /**
     * Creates a CUSTOMER waiting for email verification and emails the code (after the transaction commits).
     * No token: the customer is logged in by {@link #verifyEmail}.
     */
    @Transactional
    public AuthResult registerCustomer(String fullName, String email, String rawPhone, String password) {
        UserSummary user = userService.register(new NewUser(fullName, email, normalizePhone(rawPhone), password,
                Role.CUSTOMER, UserStatus.PENDING_EMAIL_VERIFICATION));
        emailVerification.sendCode(user.id(), user.email(), user.fullName());
        return new AuthResult(new Account(user, null), null);
    }

    /**
     * Creates a MERCHANT waiting for email verification with a PENDING profile, and emails the code. One
     * transaction, no token.
     */
    @Transactional
    public AuthResult registerMerchant(String contactPerson, String email, String rawPhone, String password,
                                       String businessName, String businessRegNo, ParcelsPerMonth parcelsPerMonth,
                                       District pickupDistrict, String city) {
        UserSummary user = userService.register(new NewUser(contactPerson, email, normalizePhone(rawPhone), password,
                Role.MERCHANT, UserStatus.PENDING_EMAIL_VERIFICATION));
        Profile profile = merchantProfiles.create(user.id(),
                new MerchantProfiles.SignUp(businessName, businessRegNo, parcelsPerMonth, pickupDistrict, city));
        emailVerification.sendCode(user.id(), user.email(), user.fullName());
        return new AuthResult(new Account(user, profile), null);
    }

    /**
     * Confirms the email with the 6-digit code. Customers become ACTIVE and get a session token (as with
     * rememberMe false); merchants become PENDING_APPROVAL and get no token.
     * 400 INVALID_CODE / CODE_EXPIRED / TOO_MANY_ATTEMPTS otherwise (also INVALID_CODE for unknown emails).
     */
    public AuthResult verifyEmail(String email, String code) {
        EmailVerificationService.Outcome outcome = emailVerification.verify(email, code);
        if (outcome.result() != CheckResult.MATCH) {
            throw outcome.result().toException();
        }
        LoginAccount account = outcome.account();
        String token = account.status() == UserStatus.ACTIVE ? issueToken(account, false) : null;
        return new AuthResult(currentAccount(account.id()), token);
    }

    public void resendVerification(String email) {
        emailVerification.resend(email);
    }

    /**
     * Unknown user, wrong password and Google-only accounts (no password) all give the same 401.
     * The account status is checked only after the password, so it is never revealed to someone who
     * doesn't know the password.
     * Not transactional on purpose: BCrypt is slow and should not hold a database connection.
     */
    public AuthResult login(String identifier, String password, boolean rememberMe) {
        Optional<LoginAccount> found = findAccount(identifier);
        String hash = found.map(LoginAccount::passwordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash == null ? dummyHash : hash);
        if (found.isEmpty() || found.get().passwordHash() == null || !passwordMatches) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        LoginAccount account = found.get();
        ensureCanLogIn(account.status());
        String token = issueToken(account, rememberMe);
        return new AuthResult(currentAccount(account.id()), token);
    }

    /**
     * "Continue with Google". Finds the account by Google id, then by email (linking it), or creates a
     * CUSTOMER with PROFILE_INCOMPLETE. Same status rules as {@link #login}; the token is a remember-me token.
     * The caller must already have checked that Google verified the email.
     */
    public AuthResult loginWithGoogle(GoogleIdentity identity) {
        LoginAccount account = userService.findOrCreateGoogleUser(identity.subject(), identity.email(),
                identity.fullName(), Role.CUSTOMER, UserStatus.PROFILE_INCOMPLETE);
        ensureCanLogIn(account.status());
        String token = issueToken(account, true);
        return new AuthResult(currentAccount(account.id()), token);
    }

    /** Finishes a Google sign-up as a customer: status ACTIVE and a new token without the restriction. */
    @Transactional
    public AuthResult completeCustomerProfile(UUID userId, String rawPhone) {
        UserSummary user = userService.completeProfile(userId, null, normalizePhone(rawPhone), Role.CUSTOMER,
                UserStatus.ACTIVE);
        String token = tokenService.issue(user.id(), user.role(), user.status(), userService.tokenVersion(user.id()),
                true);
        return new AuthResult(new Account(user, null), token);
    }

    /**
     * Finishes a Google sign-up as a merchant: MERCHANT, PENDING_APPROVAL, named after the contact person, with a
     * PENDING profile, and no token (cannot log in until approved). One transaction.
     */
    @Transactional
    public AuthResult completeMerchantProfile(UUID userId, String contactPerson, String rawPhone, String businessName,
                                              String businessRegNo, ParcelsPerMonth parcelsPerMonth,
                                              District pickupDistrict, String city) {
        UserSummary user = userService.completeProfile(userId, contactPerson, normalizePhone(rawPhone), Role.MERCHANT,
                UserStatus.PENDING_APPROVAL);
        Profile profile = merchantProfiles.create(user.id(),
                new MerchantProfiles.SignUp(businessName, businessRegNo, parcelsPerMonth, pickupDistrict, city));
        return new AuthResult(new Account(user, profile), null);
    }

    /** Always succeeds from the caller's point of view (202), whether or not the identifier matches an account. */
    public void forgotPassword(String identifier) {
        passwordService.forgot(identifier);
    }

    /**
     * Sets a new password with the code from the reset email and ends every session of the account.
     * 400 INVALID_CODE / CODE_EXPIRED / TOO_MANY_ATTEMPTS otherwise (also INVALID_CODE for unknown identifiers).
     */
    public void resetPassword(String identifier, String code, String newPassword) {
        // Hashed first (BCrypt is slow), also for unknown identifiers so timing reveals nothing.
        String newHash = passwordEncoder.encode(newPassword);
        CheckResult result = passwordService.reset(identifier, code, newHash);
        if (result != CheckResult.MATCH) {
            throw result.toException();
        }
    }

    /**
     * Changes the password of a logged-in user and ends their other sessions. Returns a new token for this session
     * ({@code rememberMe}: same lifetime as the current one). 400 with {@code fieldErrors.currentPassword} when the
     * current password is wrong or the account has none yet.
     */
    public String changePassword(UUID userId, String currentPassword, String newPassword, boolean rememberMe) {
        LoginAccount account = userService.findLoginAccountById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (account.passwordHash() == null) {
            throw new FieldValidationException("currentPassword", NO_PASSWORD_YET);
        }
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new FieldValidationException("currentPassword", WRONG_CURRENT_PASSWORD);
        }
        LoginAccount updated = passwordService.change(userId, passwordEncoder.encode(newPassword));
        return issueToken(updated, rememberMe);
    }

    /** The logged-in user; 401 if the account no longer exists. */
    @Transactional(readOnly = true)
    public Account currentAccount(UUID userId) {
        UserSummary user = userService.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        Profile merchant = user.role() == Role.MERCHANT
                ? merchantProfiles.find(userId).orElse(null)
                : null;
        return new Account(user, merchant);
    }

    /** PROFILE_INCOMPLETE may log in; its token only reaches /auth/me and /auth/complete-profile. */
    private static void ensureCanLogIn(UserStatus status) {
        switch (status) {
            case ACTIVE, PROFILE_INCOMPLETE -> { }
            case PENDING_EMAIL_VERIFICATION -> throw new ForbiddenException(ErrorCode.EMAIL_NOT_VERIFIED);
            case PENDING_APPROVAL -> throw new ForbiddenException(ErrorCode.ACCOUNT_PENDING_APPROVAL);
            case REJECTED -> throw new ForbiddenException(ErrorCode.ACCOUNT_REJECTED);
            case DISABLED -> throw new ForbiddenException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    private String issueToken(LoginAccount account, boolean rememberMe) {
        return tokenService.issue(account.id(), account.role(), account.status(), account.tokenVersion(), rememberMe);
    }

    private Optional<LoginAccount> findAccount(String identifier) {
        String value = identifier.trim();
        if (value.contains("@")) {
            return userService.findLoginAccountByEmail(value);
        }
        return PhoneNumberNormalizer.normalize(value).flatMap(userService::findLoginAccountByPhone);
    }

    /** The request was already validated, so this only fails on programming errors. */
    private static String normalizePhone(String rawPhone) {
        return PhoneNumberNormalizer.normalize(rawPhone)
                .orElseThrow(() -> new IllegalArgumentException("phone was not validated"));
    }
}
