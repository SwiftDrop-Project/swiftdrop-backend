package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserRoleCounts;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.identity.internal.domain.VerificationPurpose;
import com.swiftdrop.identity.internal.persistence.RoleTotal;
import com.swiftdrop.identity.internal.persistence.UserRepository;
import com.swiftdrop.identity.internal.security.SessionStateCache;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** User accounts. Implements {@link IdentityApi} for other modules; the auth flows use the extra methods. */
@Service
@Transactional(readOnly = true)
public class UserService implements IdentityApi {

    /** The most accounts {@link #findRecentUsers} returns. */
    static final int MAX_RECENT_USERS = 50;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionStateCache sessions;
    private final VerificationCodeService codes;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, SessionStateCache sessions,
                       VerificationCodeService codes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.codes = codes;
    }

    /** Creates a user. Throws 409 EMAIL_ALREADY_REGISTERED / PHONE_ALREADY_REGISTERED on duplicates. */
    @Transactional
    public UserSummary register(NewUser command) {
        String email = normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (users.existsByPhone(command.phone())) {
            throw new ConflictException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        User user = new User(command.fullName().trim(), email, command.phone(),
                passwordEncoder.encode(command.rawPassword()), command.role(), command.status());
        return toView(users.saveAndFlush(user));
    }

    @Override
    @Transactional
    public UserSummary registerUser(String fullName, String email, String normalizedPhone, String rawPassword,
                                    Role role, UserStatus status) {
        return register(new NewUser(fullName, email, normalizedPhone, rawPassword, role, status));
    }

    @Override
    public Optional<UserSummary> findByEmail(String email) {
        return users.findByEmail(normalizeEmail(email)).map(UserService::toView);
    }

    public boolean existsByEmail(String email) {
        return users.existsByEmail(normalizeEmail(email));
    }

    public boolean existsByPhone(String normalizedPhone) {
        return users.existsByPhone(normalizedPhone);
    }

    public Optional<LoginAccount> findLoginAccountByEmail(String email) {
        return users.findByEmail(normalizeEmail(email)).map(UserService::toLoginAccount);
    }

    /** {@code normalizedPhone} must already be in +947XXXXXXXX form. */
    public Optional<LoginAccount> findLoginAccountByPhone(String normalizedPhone) {
        return users.findByPhone(normalizedPhone).map(UserService::toLoginAccount);
    }

    public Optional<LoginAccount> findLoginAccountById(UUID id) {
        return users.findById(id).map(UserService::toLoginAccount);
    }

    @Override
    public Optional<UserSummary> findById(UUID id) {
        return users.findById(id).map(UserService::toView);
    }

    @Override
    public List<UserSummary> findAllById(Collection<UUID> ids) {
        return users.findAllById(ids).stream().map(UserService::toView).toList();
    }

    @Override
    public List<CourierSummary> listActiveCouriers() {
        return users.findByRoleAndStatusOrderByFullNameAsc(Role.COURIER, UserStatus.ACTIVE).stream()
                .map(UserService::toCourierSummary)
                .toList();
    }

    @Override
    public List<CourierSummary> listActiveCouriersByBranch(District branch) {
        Objects.requireNonNull(branch, "branch");
        return users.findByRoleAndStatusAndBranchOrderByFullNameAsc(Role.COURIER, UserStatus.ACTIVE, branch).stream()
                .map(UserService::toCourierSummary)
                .toList();
    }

    @Override
    public CourierSummary requireAssignableCourier(UUID courierId) {
        Objects.requireNonNull(courierId, "courierId");
        User user = users.findById(courierId)
                .filter(u -> u.getRole() == Role.COURIER)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessRuleException(ErrorCode.COURIER_NOT_ASSIGNABLE);
        }
        return toCourierSummary(user);
    }

    @Override
    public UserRoleCounts getUserRoleCounts() {
        Map<Role, Long> perRole = new EnumMap<>(Role.class);
        for (RoleTotal row : users.countPerRoleExcluding(UserStatus.REMOVED)) {
            perRole.put(row.getRole(), row.getTotal());
        }
        long total = perRole.values().stream().mapToLong(Long::longValue).sum();
        return new UserRoleCounts(total,
                perRole.getOrDefault(Role.CUSTOMER, 0L),
                perRole.getOrDefault(Role.MERCHANT, 0L),
                perRole.getOrDefault(Role.COURIER, 0L),
                perRole.getOrDefault(Role.DISPATCHER, 0L),
                perRole.getOrDefault(Role.ADMIN, 0L),
                users.countByStatus(UserStatus.PENDING_APPROVAL));
    }

    @Override
    public List<UserSummary> findRecentUsers(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        return users.findByStatusNotOrderByCreatedAtDesc(UserStatus.REMOVED,
                        Limit.of(Math.min(limit, MAX_RECENT_USERS))).stream()
                .map(UserService::toView)
                .toList();
    }

    /**
     * The account for a verified Google identity: found by {@code googleSub}, else by email (and the Google
     * account is linked to it), else a new user with the given role and status, without password or phone.
     * Throws 409 EMAIL_ALREADY_REGISTERED if the email belongs to an account linked to a different Google account.
     *
     * <p>Disabled, removed and rejected accounts are never modified or linked through Google sign-in; they are
     * returned unchanged so the caller rejects them with their status error.
     *
     * <p>Linking to an account still waiting for email verification verifies it (Google has verified the email),
     * and removes its password and sessions: whoever chose that password never proved they own the address.
     */
    @Transactional
    public LoginAccount findOrCreateGoogleUser(String googleSub, String email, String fullName, Role newUserRole,
                                               UserStatus newUserStatus) {
        Optional<User> linked = users.findByGoogleSub(googleSub);
        if (linked.isPresent()) {
            return toLoginAccount(linked.get());
        }
        String normalizedEmail = normalizeEmail(email);
        Optional<User> sameEmail = users.findByEmail(normalizedEmail);
        if (sameEmail.isPresent()) {
            User user = sameEmail.get();
            if (user.getStatus() == UserStatus.DISABLED
                    || user.getStatus() == UserStatus.REMOVED
                    || user.getStatus() == UserStatus.REJECTED) {
                return toLoginAccount(user);
            }
            if (user.getGoogleSub() != null) {
                throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            user.linkGoogle(googleSub);
            if (user.markEmailVerified()) {
                user.removePassword();
                codes.invalidateAll(user.getId(), VerificationPurpose.EMAIL_VERIFICATION);
                sessions.evict(user.getId());
            }
            return toLoginAccount(users.saveAndFlush(user));
        }
        User created = User.fromGoogle(googleSub, fullName.trim(), normalizedEmail, newUserRole, newUserStatus);
        return toLoginAccount(users.saveAndFlush(created));
    }

    /**
     * Finishes a Google sign-up: sets the phone (and optionally a new name), role and status.
     * 409 PROFILE_ALREADY_COMPLETE unless the user is PROFILE_INCOMPLETE; 409 PHONE_ALREADY_REGISTERED on duplicates.
     * {@code normalizedPhone} must already be in +947XXXXXXXX form; {@code fullName} null keeps the current name.
     */
    @Transactional
    public UserSummary completeProfile(UUID id, String fullName, String normalizedPhone, Role role, UserStatus status) {
        User user = users.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (user.getStatus() != UserStatus.PROFILE_INCOMPLETE) {
            throw new ConflictException(ErrorCode.PROFILE_ALREADY_COMPLETE);
        }
        if (users.existsByPhone(normalizedPhone)) {
            throw new ConflictException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        user.completeProfile(fullName == null ? null : fullName.trim(), normalizedPhone, role, status);
        sessions.evict(id);
        return toView(users.saveAndFlush(user));
    }

    /** The user's current token version (the "ver" claim of new tokens). */
    public int tokenVersion(UUID id) {
        return users.findById(id).map(User::getTokenVersion)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    /**
     * Sets the account status. Removed accounts cannot be restored. Sessions of an account that is no longer ACTIVE
     * (e.g. DISABLED) stop working within 30 seconds, at once on this backend instance.
     */
    @Override
    @Transactional
    public UserSummary changeStatus(UUID id, UserStatus status) {
        User user = users.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (user.getStatus() == UserStatus.REMOVED) {
            throw new ConflictException(ErrorCode.ACCOUNT_REMOVED);
        }
        user.changeStatus(status);
        sessions.evict(id);
        return toView(users.saveAndFlush(user));
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static UserSummary toView(User user) {
        return new UserSummary(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.getStatus(), user.isMustChangePassword(), user.getCreatedAt());
    }

    static LoginAccount toLoginAccount(User user) {
        return new LoginAccount(user.getId(), user.getPasswordHash(), user.getRole(), user.getStatus(),
                user.getTokenVersion(), user.isMustChangePassword());
    }

    static CourierSummary toCourierSummary(User user) {
        return new CourierSummary(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getBranch(), user.getVehicleType(), user.getStatus());
    }
}
