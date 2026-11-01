package com.swiftdrop.users.application;

import com.swiftdrop.shared.exception.ApiException;
import com.swiftdrop.shared.exception.ErrorCode;
import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.User;
import com.swiftdrop.users.domain.UserStatus;
import com.swiftdrop.users.infrastructure.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates a user. Throws 409 EMAIL_ALREADY_REGISTERED / PHONE_ALREADY_REGISTERED on duplicates. */
    @Transactional
    public UserView register(NewUser command) {
        String email = normalizeEmail(command.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (users.existsByPhone(command.phone())) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        User user = new User(command.fullName().trim(), email, command.phone(),
                passwordEncoder.encode(command.rawPassword()), command.role(), command.status());
        return toView(users.saveAndFlush(user));
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

    public Optional<UserView> findById(UUID id) {
        return users.findById(id).map(UserService::toView);
    }

    public List<UserView> findAllById(Collection<UUID> ids) {
        return users.findAllById(ids).stream().map(UserService::toView).toList();
    }

    /**
     * The account for a verified Google identity: found by {@code googleSub}, else by email (and the Google
     * account is linked to it), else a new user with the given role and status, without password or phone.
     * Throws 409 EMAIL_ALREADY_REGISTERED if the email belongs to an account linked to a different Google account.
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
            if (user.getGoogleSub() != null) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            user.linkGoogle(googleSub);
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
    public UserView completeProfile(UUID id, String fullName, String normalizedPhone, Role role, UserStatus status) {
        User user = users.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (user.getStatus() != UserStatus.PROFILE_INCOMPLETE) {
            throw new ApiException(ErrorCode.PROFILE_ALREADY_COMPLETE);
        }
        if (users.existsByPhone(normalizedPhone)) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        user.completeProfile(fullName == null ? null : fullName.trim(), normalizedPhone, role, status);
        return toView(users.saveAndFlush(user));
    }

    @Transactional
    public UserView changeStatus(UUID id, UserStatus status) {
        User user = users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        user.changeStatus(status);
        return toView(users.saveAndFlush(user));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserView toView(User user) {
        return new UserView(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.getStatus(), user.getCreatedAt());
    }

    private static LoginAccount toLoginAccount(User user) {
        return new LoginAccount(user.getId(), user.getPasswordHash(), user.getRole(), user.getStatus());
    }
}
