package com.swiftdrop.users.application;

import com.swiftdrop.shared.exception.ApiException;
import com.swiftdrop.shared.exception.ErrorCode;
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
