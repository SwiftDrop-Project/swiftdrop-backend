package com.swiftdrop.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the identity module. The only way to read or change users from outside identity.
 */
public interface IdentityApi {

    Optional<UserSummary> findById(UUID id);

    /** Unknown ids are skipped. */
    List<UserSummary> findAllById(Collection<UUID> ids);

    /** Case-insensitive. */
    Optional<UserSummary> findByEmail(String email);

    /** Sets the account status, e.g. ACTIVE after an admin approves a merchant. 404 NOT_FOUND for an unknown id. */
    UserSummary changeStatus(UUID id, UserStatus status);

    /**
     * Creates a password account. {@code normalizedPhone} must already be in +947XXXXXXXX form; the password is hashed
     * here. 409 EMAIL_ALREADY_REGISTERED / PHONE_ALREADY_REGISTERED on duplicates. Used for seeding demo accounts;
     * public sign-up goes through POST /auth/register.
     */
    UserSummary registerUser(String fullName, String email, String normalizedPhone, String rawPassword, Role role,
                             UserStatus status);
}
